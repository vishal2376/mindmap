package com.mindmap.plugin.analysis

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class KotlinAnalyzer(
    private val project: Project,
    private val maxOutboundDepth: Int = 3,
    private val maxInboundDepth: Int = 2
) : LanguageAnalyzer {

    companion object {
        private val LOG = Logger.getInstance(KotlinAnalyzer::class.java)
        private const val MAX_NODES = 300
        private const val MAX_CALLS_PER_FUNCTION = 50
        private const val MAX_INBOUND_REFS = 30
    }

    override fun canAnalyze(psiFile: PsiFile): Boolean = psiFile is KtFile

    override fun findFunctionAtOffset(psiFile: PsiFile, offset: Int): PsiElement? {
        val element = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
    }

    override fun languageName(): String = "Kotlin"

    override fun buildGraph(element: PsiElement, indicator: ProgressIndicator?): GraphData {
        val function = element as? KtNamedFunction
            ?: throw IllegalArgumentException("Element is not a KtNamedFunction: ${element.javaClass.simpleName}")
        return buildKotlinGraph(function, indicator)
    }

    fun buildKotlinGraph(function: KtNamedFunction, indicator: ProgressIndicator? = null): GraphData {
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
        if (currentDepth > maxOutboundDepth) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val callerId = getFunctionId(function)
        val callExpressions = PsiTreeUtil.findChildrenOfType(function.bodyExpression, KtCallExpression::class.java)

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
                        val targetPsiRaw = targetSymbol.psi ?: continue

                        // Handle Kotlin function targets
                        val targetKt = targetPsiRaw as? KtNamedFunction
                        // Handle Java method targets (cross-language)
                        val targetJava = if (targetKt == null) targetPsiRaw as? PsiMethod else null

                        if (targetKt != null) {
                            val targetId = getFunctionId(targetKt)
                            val isLibrary = !isSourceCode(targetKt)

                            if (!nodes.containsKey(targetId)) {
                                nodes[targetId] = createNode(targetKt, NodeType.OUTBOUND, currentDepth, isLibrary)
                            }

                            addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                            if (!isLibrary && currentDepth < maxOutboundDepth) {
                                if (targetKt.bodyExpression != null) {
                                    analyzeOutbound(targetKt, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                                } else {
                                    val impls = findImplementations(targetKt)
                                    for (impl in impls.take(3)) {
                                        if (nodes.size >= MAX_NODES) break
                                        val implId = getFunctionId(impl)
                                        if (!nodes.containsKey(implId)) {
                                            nodes[implId] = createNode(impl, NodeType.OUTBOUND, currentDepth + 1)
                                        }
                                        addEdgeIfNew(targetId, implId, nodes, edges, edgeKeys)
                                    }
                                }
                            }
                        } else if (targetJava != null) {
                            // Cross-language: Kotlin calling Java
                            val targetId = getJavaMethodId(targetJava)
                            val isLibrary = !isJavaSourceCode(targetJava)

                            if (!nodes.containsKey(targetId)) {
                                nodes[targetId] = createJavaNode(targetJava, NodeType.OUTBOUND, currentDepth, isLibrary)
                            }

                            addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                            // Don't recurse into Java methods from Kotlin analyzer — keep it as a leaf
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
        if (currentDepth > maxInboundDepth) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val targetId = getFunctionId(function)
        val scope = GlobalSearchScope.projectScope(project)
        val references = try {
            val allRefs = ReferencesSearch.search(function, scope).findAll()
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

                if (!isLibrary && currentDepth < maxInboundDepth) {
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
        val paramSig = function.valueParameters.joinToString(",") { it.typeReference?.text ?: "Any" }
        val fqName = function.fqName?.asString()
        if (fqName != null) return "$fqName($paramSig)"

        val filePath = function.containingFile?.virtualFile?.path ?: function.containingFile?.name ?: "unknown"
        val name = function.name ?: "anonymous"
        return "$filePath::$name($paramSig)"
    }

    private fun buildSignature(function: KtNamedFunction): String {
        val params = function.valueParameters.joinToString(", ") { param ->
            "${param.name ?: "_"}: ${param.typeReference?.text ?: "Any"}"
        }
        val returnType = function.typeReference?.text ?: ""
        val returnSuffix = if (returnType.isNotEmpty()) ": $returnType" else ""
        return "${function.name}($params)$returnSuffix"
    }

    private fun findImplementations(function: KtNamedFunction): List<KtNamedFunction> {
        if (function.bodyExpression != null) return emptyList()
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            function.toLightMethods().flatMap { lightMethod ->
                OverridingMethodsSearch.search(lightMethod, scope, true).toList()
                    .mapNotNull { it.navigationElement as? KtNamedFunction }
                    .filter { impl -> impl.bodyExpression != null && isSourceCode(impl) }
            }.take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isSourceCode(function: KtNamedFunction): Boolean {
        val virtualFile = function.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }

    // Cross-language helpers for Java targets called from Kotlin
    private fun getJavaMethodId(method: PsiMethod): String {
        val containingClass = method.containingClass?.qualifiedName ?: ""
        val paramSig = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return if (containingClass.isNotEmpty()) {
            "$containingClass.${method.name}($paramSig)"
        } else {
            val filePath = method.containingFile?.virtualFile?.path ?: "unknown"
            "$filePath::${method.name}($paramSig)"
        }
    }

    private fun createJavaNode(
        method: PsiMethod,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        val id = getJavaMethodId(method)
        val params = method.parameterList.parameters.joinToString(", ") { "${it.name}: ${it.type.presentableText}" }
        val returnType = method.returnType?.presentableText ?: "void"
        val signature = "${method.name}($params): $returnType"

        return CallGraphNode(
            id = id,
            name = method.name,
            signature = signature,
            fileName = method.containingFile?.name ?: "",
            type = type,
            depth = depth,
            isLibrary = isLibrary,
            loc = method.text?.lines()?.size ?: 0
        ).also {
            it.psiElement = method
        }
    }

    private fun isJavaSourceCode(method: PsiMethod): Boolean {
        val virtualFile = method.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }
}
