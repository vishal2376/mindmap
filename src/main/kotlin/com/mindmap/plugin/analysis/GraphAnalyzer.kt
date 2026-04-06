package com.mindmap.plugin.analysis

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

class GraphAnalyzer(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(GraphAnalyzer::class.java)
        private const val MAX_OUTBOUND_DEPTH = 3
        private const val MAX_INBOUND_DEPTH = 2
        private const val MAX_NODES = 300
        private const val MAX_CALLS_PER_FUNCTION = 50
        private const val MAX_INBOUND_REFS = 30
    }

    fun buildGraph(function: KtNamedFunction, indicator: ProgressIndicator? = null): GraphData {
        val nodes = mutableMapOf<String, CallGraphNode>()
        val edges = mutableListOf<CallGraphEdge>()
        val edgeKeys = HashSet<String>()

        try {
            runReadAction {
                val rootId = getFunctionId(function)
                val rootNode = createNode(function, NodeType.ROOT, 0)
                nodes[rootId] = rootNode

                indicator?.text = "Analyzing outbound calls..."
                analyzeOutbound(function, nodes, edges, edgeKeys, 1, indicator)
                indicator?.text = "Finding callers..."
                analyzeInbound(function, nodes, edges, edgeKeys, 1, indicator)
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.error("Error building graph", e)
        }

        return GraphData(nodes.values.toList(), edges)
    }

    private fun edgeKey(from: String, to: String): String {
        return "$from\u0000$to"
    }

    private fun addEdgeIfNew(
        from: String, to: String,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>
    ): Boolean {
        val key = edgeKey(from, to)
        if (edgeKeys.add(key)) {
            edges.add(CallGraphEdge(from, to))
            nodes[from]?.children?.add(to)
            nodes[to]?.parents?.add(from)
            return true
        }
        return false
    }

    private fun analyzeOutbound(
        function: KtNamedFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > MAX_OUTBOUND_DEPTH) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val callerId = getFunctionId(function)
        val callExpressions = PsiTreeUtil.findChildrenOfType(function.bodyExpression, KtCallExpression::class.java)

        // Limit calls per function to prevent explosion on large functions
        val limitedCalls = if (callExpressions.size > MAX_CALLS_PER_FUNCTION) {
            callExpressions.take(MAX_CALLS_PER_FUNCTION)
        } else {
            callExpressions.toList()
        }

        try {
            analyze(function) {
                for (callExpr in limitedCalls) {
                    if (nodes.size >= MAX_NODES) break
                    try {
                        val resolvedCall = callExpr.resolveToCall()?.singleFunctionCallOrNull() ?: continue
                        val targetSymbol = resolvedCall.partiallyAppliedSymbol.symbol
                        val targetPsi = targetSymbol.psi as? KtNamedFunction ?: continue

                        val targetId = getFunctionId(targetPsi)
                        val isLibrary = !isSourceCode(targetPsi)

                        if (!nodes.containsKey(targetId)) {
                            nodes[targetId] = createNode(targetPsi, NodeType.OUTBOUND, currentDepth, isLibrary)
                        }

                        addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                        if (!isLibrary && currentDepth < MAX_OUTBOUND_DEPTH) {
                            analyzeOutbound(targetPsi, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                        }
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (_: Exception) {
                        // Skip unresolvable calls
                    }
                }
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error in outbound analysis at depth $currentDepth", e)
        }
    }

    private fun analyzeInbound(
        function: KtNamedFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > MAX_INBOUND_DEPTH) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val targetId = getFunctionId(function)
        val scope = GlobalSearchScope.projectScope(project)
        val references = try {
            val allRefs = ReferencesSearch.search(function, scope).findAll()
            // Limit inbound refs to prevent explosion on widely-used utility functions
            if (allRefs.size > MAX_INBOUND_REFS) allRefs.take(MAX_INBOUND_REFS) else allRefs
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching references", e)
            return
        }

        for (ref in references) {
            if (nodes.size >= MAX_NODES) break
            try {
                val callerFunction = PsiTreeUtil.getParentOfType(
                    ref.element, KtNamedFunction::class.java
                ) ?: continue

                val callerId = getFunctionId(callerFunction)
                val isLibrary = !isSourceCode(callerFunction)

                if (!nodes.containsKey(callerId)) {
                    nodes[callerId] = createNode(callerFunction, NodeType.INBOUND, currentDepth, isLibrary)
                }

                addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                if (!isLibrary && currentDepth < MAX_INBOUND_DEPTH) {
                    analyzeInbound(callerFunction, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                }
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (_: Exception) {
                // Skip unresolvable references
            }
        }
    }

    private fun createNode(
        function: KtNamedFunction,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        val id = getFunctionId(function)
        val name = function.name ?: "anonymous"
        val signature = buildSignature(function)
        val fileName = function.containingFile?.name ?: ""
        val loc = function.text?.lines()?.size ?: 0

        return CallGraphNode(
            id = id,
            name = name,
            signature = signature,
            fileName = fileName,
            type = type,
            depth = depth,
            isLibrary = isLibrary,
            loc = loc
        ).also {
            it.psiElement = function
        }
    }

    private fun getFunctionId(function: KtNamedFunction): String {
        val fqName = function.fqName?.asString()
        if (fqName != null) return fqName

        val containingFile = function.containingFile?.name ?: "unknown"
        val name = function.name ?: "anonymous"
        return "$containingFile::$name"
    }

    private fun buildSignature(function: KtNamedFunction): String {
        val params = function.valueParameters.joinToString(", ") { param ->
            "${param.name ?: "_"}: ${param.typeReference?.text ?: "Any"}"
        }
        val returnType = function.typeReference?.text ?: ""
        val returnSuffix = if (returnType.isNotEmpty()) ": $returnType" else ""
        return "${function.name}($params)$returnSuffix"
    }

    private fun isSourceCode(function: KtNamedFunction): Boolean {
        val virtualFile = function.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }
}
