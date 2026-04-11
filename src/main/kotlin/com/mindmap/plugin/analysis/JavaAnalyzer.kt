package com.mindmap.plugin.analysis

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction

class JavaAnalyzer(
    private val project: Project,
    private val maxOutboundDepth: Int = 3,
    private val maxInboundDepth: Int = 2
) : LanguageAnalyzer {

    companion object {
        private val LOG = Logger.getInstance(JavaAnalyzer::class.java)
        private const val MAX_NODES = 300
        private const val MAX_CALLS_PER_METHOD = 50
        private const val MAX_INBOUND_REFS = 30
    }

    override fun canAnalyze(psiFile: PsiFile): Boolean = psiFile is PsiJavaFile

    override fun findFunctionAtOffset(psiFile: PsiFile, offset: Int): PsiElement? {
        val element = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    override fun languageName(): String = "Java"

    override fun buildGraph(element: PsiElement, indicator: ProgressIndicator?): GraphData {
        val method = element as? PsiMethod
            ?: throw IllegalArgumentException("Element is not a PsiMethod: ${element.javaClass.simpleName}")

        val nodes = mutableMapOf<String, CallGraphNode>()
        val edges = mutableListOf<CallGraphEdge>()
        val edgeKeys = HashSet<String>()

        try {
            runReadAction {
                val rootId = getMethodId(method)
                val rootNode = createNode(method, NodeType.ROOT, 0)
                nodes[rootId] = rootNode

                indicator?.text = "Analyzing outbound calls..."
                analyzeOutbound(method, nodes, edges, edgeKeys, 1, indicator)
                indicator?.text = "Finding callers..."
                analyzeInbound(method, nodes, edges, edgeKeys, 1, indicator)
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.error("Error building Java graph", e)
        }

        return GraphData(nodes.values.toList(), edges)
    }

    private fun addEdgeIfNew(
        from: String, to: String,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>
    ): Boolean {
        val key = "$from\u0000$to"
        if (edgeKeys.add(key)) {
            edges.add(CallGraphEdge(from, to))
            nodes[from]?.children?.add(to)
            nodes[to]?.parents?.add(from)
            return true
        }
        return false
    }

    private fun analyzeOutbound(
        method: PsiMethod,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > maxOutboundDepth) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val callerId = getMethodId(method)
        val body = method.body ?: return
        val callExpressions = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)

        val limitedCalls = if (callExpressions.size > MAX_CALLS_PER_METHOD) {
            callExpressions.take(MAX_CALLS_PER_METHOD)
        } else {
            callExpressions.toList()
        }

        for (callExpr in limitedCalls) {
            if (nodes.size >= MAX_NODES) break
            try {
                val targetMethod = callExpr.resolveMethod() ?: continue

                // Check if the resolved method is actually a Kotlin function (cross-language)
                val ktFunction = targetMethod.navigationElement as? KtNamedFunction
                if (ktFunction != null) {
                    // Cross-language: Java calling Kotlin — create node with Kotlin PSI
                    val targetId = getKotlinFunctionId(ktFunction)
                    val isLibrary = !isKotlinSourceCode(ktFunction)

                    if (!nodes.containsKey(targetId)) {
                        nodes[targetId] = createKotlinNode(ktFunction, NodeType.OUTBOUND, currentDepth, isLibrary)
                    }
                    addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)
                    // Don't recurse into Kotlin from Java analyzer — keep as leaf
                    continue
                }

                val targetId = getMethodId(targetMethod)
                val isLibrary = !isSourceCode(targetMethod)

                if (!nodes.containsKey(targetId)) {
                    nodes[targetId] = createNode(targetMethod, NodeType.OUTBOUND, currentDepth, isLibrary)
                }

                addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                if (!isLibrary && currentDepth < maxOutboundDepth) {
                    if (targetMethod.body != null) {
                        analyzeOutbound(targetMethod, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                    } else {
                        // Abstract/interface — find implementations
                        val impls = findImplementations(targetMethod)
                        for (impl in impls.take(3)) {
                            if (nodes.size >= MAX_NODES) break
                            val implId = getMethodId(impl)
                            if (!nodes.containsKey(implId)) {
                                nodes[implId] = createNode(impl, NodeType.OUTBOUND, currentDepth + 1)
                            }
                            addEdgeIfNew(targetId, implId, nodes, edges, edgeKeys)
                        }
                    }
                }
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (_: Exception) {
                // Skip unresolvable calls
            }
        }
    }

    private fun analyzeInbound(
        method: PsiMethod,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > maxInboundDepth) return
        if (nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val targetId = getMethodId(method)
        val scope = GlobalSearchScope.projectScope(project)
        val references = try {
            val allRefs = ReferencesSearch.search(method, scope).findAll()
            if (allRefs.size > MAX_INBOUND_REFS) allRefs.take(MAX_INBOUND_REFS) else allRefs
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching Java references", e)
            return
        }

        for (ref in references) {
            if (nodes.size >= MAX_NODES) break
            try {
                val callerMethod = PsiTreeUtil.getParentOfType(
                    ref.element, PsiMethod::class.java
                ) ?: continue

                val callerId = getMethodId(callerMethod)
                val isLibrary = !isSourceCode(callerMethod)

                if (!nodes.containsKey(callerId)) {
                    nodes[callerId] = createNode(callerMethod, NodeType.INBOUND, currentDepth, isLibrary)
                }

                addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                if (!isLibrary && currentDepth < maxInboundDepth) {
                    analyzeInbound(callerMethod, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                }
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (_: Exception) {
                // Skip unresolvable references
            }
        }
    }

    private fun createNode(
        method: PsiMethod,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        val id = getMethodId(method)
        val name = method.name
        val signature = buildSignature(method)
        val fileName = method.containingFile?.name ?: ""
        val loc = method.text?.lines()?.size ?: 0

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
            it.psiElement = method
        }
    }

    private fun getMethodId(method: PsiMethod): String {
        val containingClass = method.containingClass?.qualifiedName ?: ""
        val paramSig = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return if (containingClass.isNotEmpty()) {
            "$containingClass.${method.name}($paramSig)"
        } else {
            val filePath = method.containingFile?.virtualFile?.path ?: "unknown"
            "$filePath::${method.name}($paramSig)"
        }
    }

    private fun buildSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.name}: ${param.type.presentableText}"
        }
        val returnType = method.returnType?.presentableText ?: "void"
        return "${method.name}($params): $returnType"
    }

    private fun findImplementations(method: PsiMethod): List<PsiMethod> {
        if (method.body != null) return emptyList()
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            OverridingMethodsSearch.search(method, scope, true).toList()
                .filter { impl -> impl.body != null && isSourceCode(impl) }
                .take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isSourceCode(method: PsiMethod): Boolean {
        val virtualFile = method.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }

    // Cross-language helpers for Kotlin targets called from Java
    private fun getKotlinFunctionId(function: KtNamedFunction): String {
        val paramSig = function.valueParameters.joinToString(",") { it.typeReference?.text ?: "Any" }
        val fqName = function.fqName?.asString()
        if (fqName != null) return "$fqName($paramSig)"
        val filePath = function.containingFile?.virtualFile?.path ?: function.containingFile?.name ?: "unknown"
        val name = function.name ?: "anonymous"
        return "$filePath::$name($paramSig)"
    }

    private fun createKotlinNode(
        function: KtNamedFunction,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        val id = getKotlinFunctionId(function)
        val params = function.valueParameters.joinToString(", ") { "${it.name ?: "_"}: ${it.typeReference?.text ?: "Any"}" }
        val returnType = function.typeReference?.text ?: ""
        val returnSuffix = if (returnType.isNotEmpty()) ": $returnType" else ""
        val signature = "${function.name}($params)$returnSuffix"

        return CallGraphNode(
            id = id,
            name = function.name ?: "anonymous",
            signature = signature,
            fileName = function.containingFile?.name ?: "",
            type = type,
            depth = depth,
            isLibrary = isLibrary,
            loc = function.text?.lines()?.size ?: 0
        ).also {
            it.psiElement = function
        }
    }

    private fun isKotlinSourceCode(function: KtNamedFunction): Boolean {
        val virtualFile = function.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }
}
