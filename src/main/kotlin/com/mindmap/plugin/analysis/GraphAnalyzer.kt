package com.mindmap.plugin.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Builds a bidirectional call graph for a given Kotlin function.
 *
 * Outbound traversal follows calls made by the function (callees).
 * Inbound traversal finds all callers of the function.
 * Both directions are bounded by [maxOutboundDepth] / [maxInboundDepth] and by [MAX_NODES]
 * to keep graph size manageable. Library functions (outside project source) are included as
 * leaf nodes but are not recursed into.
 *
 * All analysis runs inside a [ReadAction.run] block on a background thread.
 */
class GraphAnalyzer(
    private val project: Project,
    private val maxOutboundDepth: Int = 3,
    private val maxInboundDepth: Int = 2,
    private val debugMode: Boolean = false
) {

    companion object {
        private val LOG = Logger.getInstance(GraphAnalyzer::class.java)
        private const val MAX_NODES = 300
        private const val MAX_CALLS_PER_FUNCTION = 50
        private const val MAX_INBOUND_REFS = 30
    }

    private fun debug(msg: () -> String) { if (debugMode) com.mindmap.plugin.DebugLog.log(msg()) }

    /** Builds and returns the full call graph. Must be called on a background thread. */
    fun buildGraph(function: KtNamedFunction, indicator: ProgressIndicator? = null): GraphData {
        val nodes = mutableMapOf<String, CallGraphNode>()
        val edges = mutableListOf<CallGraphEdge>()
        val edgeKeys = HashSet<String>()
        val startTime = System.currentTimeMillis()

        debug { "buildGraph: root=${function.name}, file=${function.containingFile?.name}, outDepth=$maxOutboundDepth, inDepth=$maxInboundDepth" }

        try {
            ReadAction.run<RuntimeException> {
                val rootId = getFunctionId(function)
                nodes[rootId] = createNode(function, NodeType.ROOT, 0)

                indicator?.text = "Analyzing outbound calls..."
                if (function.bodyExpression != null) {
                    analyzeOutbound(function, nodes, edges, edgeKeys, 1, indicator)
                } else {
                    debug { "buildGraph: root has no body, searching implementations" }
                    for (impl in findImplementations(function)) {
                        if (nodes.size >= MAX_NODES) break
                        val implId = getFunctionId(impl)
                        if (!nodes.containsKey(implId)) nodes[implId] = createNode(impl, NodeType.OUTBOUND, 1)
                        addEdgeIfNew(rootId, implId, nodes, edges, edgeKeys)
                        analyzeOutbound(impl, nodes, edges, edgeKeys, 2, indicator)
                    }
                }
                debug { "buildGraph: outbound done — ${nodes.size} nodes, ${edges.size} edges" }
                indicator?.text = "Finding callers..."
                analyzeInbound(function, nodes, edges, edgeKeys, 1, indicator)
                debug { "buildGraph: inbound done — ${nodes.size} nodes, ${edges.size} edges" }
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.error("Error building graph", e)
        }

        debug { "buildGraph: completed in ${System.currentTimeMillis() - startTime}ms — ${nodes.size} nodes, ${edges.size} edges" }
        return GraphData(nodes.values.toList(), edges)
    }

    private fun edgeKey(from: String, to: String) = "$from\u0000$to"

    private fun addEdgeIfNew(
        from: String, to: String,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>
    ): Boolean {
        if (!edgeKeys.add(edgeKey(from, to))) return false
        edges.add(CallGraphEdge(from, to))
        nodes[from]?.children?.add(to)
        nodes[to]?.parents?.add(from)
        return true
    }

    private fun analyzeOutbound(
        function: KtNamedFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > maxOutboundDepth || nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val callerId = getFunctionId(function)
        val calls = PsiTreeUtil.findChildrenOfType(function.bodyExpression, KtCallExpression::class.java)
            .let { if (it.size > MAX_CALLS_PER_FUNCTION) it.take(MAX_CALLS_PER_FUNCTION) else it.toList() }

        try {
            // resolveToCall() is a KaSession extension, so resolution must stay inside analyze{}.
            // processOutboundTarget handles everything that doesn't need the analysis context.
            analyze(function) {
                for (callExpr in calls) {
                    if (nodes.size >= MAX_NODES) break
                    try {
                        val resolvedCall = callExpr.resolveToCall()?.singleFunctionCallOrNull() ?: continue
                        val targetPsi = resolvedCall.symbol.psi as? KtNamedFunction ?: continue
                        if (targetPsi.name.isNullOrEmpty()) continue
                        processOutboundTarget(callerId, targetPsi, nodes, edges, edgeKeys, currentDepth, indicator)
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (e: Exception) { LOG.debug("Skipped unresolved outbound call at depth $currentDepth", e) }
                }
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error in outbound analysis at depth $currentDepth", e)
        }
    }

    /**
     * Adds [targetPsi] to the graph and recurses into it if it is a project function.
     * Called inside an [analyze] block so it has access to resolved PSI but does not
     * need the Kotlin Analysis API directly.
     */
    private fun processOutboundTarget(
        callerId: String,
        targetPsi: KtNamedFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator?
    ) {
        val targetId = getFunctionId(targetPsi)
        val isLibrary = !isSourceCode(targetPsi)
        if (!nodes.containsKey(targetId)) nodes[targetId] = createNode(targetPsi, NodeType.OUTBOUND, currentDepth, isLibrary)
        addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

        if (!isLibrary && currentDepth < maxOutboundDepth) {
            if (targetPsi.bodyExpression != null) {
                analyzeOutbound(targetPsi, nodes, edges, edgeKeys, currentDepth + 1, indicator)
            } else {
                expandAbstractOutbound(targetId, targetPsi, nodes, edges, edgeKeys, currentDepth, indicator)
            }
        }
    }

    // Abstract/interface target: follow concrete implementations instead of the declaration.
    private fun expandAbstractOutbound(
        targetId: String,
        targetPsi: KtNamedFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator?
    ) {
        for (impl in findImplementations(targetPsi)) {
            if (nodes.size >= MAX_NODES) break
            val implId = getFunctionId(impl)
            if (!nodes.containsKey(implId)) nodes[implId] = createNode(impl, NodeType.OUTBOUND, currentDepth + 1)
            addEdgeIfNew(targetId, implId, nodes, edges, edgeKeys)
            if (currentDepth + 1 < maxOutboundDepth) {
                analyzeOutbound(impl, nodes, edges, edgeKeys, currentDepth + 2, indicator)
            }
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
        if (currentDepth > maxInboundDepth || nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val targetId = getFunctionId(function)
        val scope = GlobalSearchScope.projectScope(project)
        val references = collectInboundReferences(function, scope) ?: return

        for (ref in references) {
            if (nodes.size >= MAX_NODES) break
            try {
                val callerFunction = PsiTreeUtil.getParentOfType(ref.element, KtNamedFunction::class.java) ?: continue
                val callerId = getFunctionId(callerFunction)
                val isLibrary = !isSourceCode(callerFunction)

                if (!nodes.containsKey(callerId)) nodes[callerId] = createNode(callerFunction, NodeType.INBOUND, currentDepth, isLibrary)
                addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

                if (!isLibrary && currentDepth < maxInboundDepth) {
                    analyzeInbound(callerFunction, nodes, edges, edgeKeys, currentDepth + 1, indicator)
                }
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (e: Exception) { LOG.debug("Skipped unresolved inbound reference at depth $currentDepth", e) }
        }
    }

    /**
     * Collects all call sites for [function] within the project scope.
     * Super declarations are included because callers often call the abstract base;
     * searching only the concrete override would miss those sites.
     * Results are capped at [MAX_INBOUND_REFS] to prevent runaway searches on widely-used functions.
     */
    private fun collectInboundReferences(
        function: KtNamedFunction,
        scope: GlobalSearchScope
    ): List<PsiReference>? {
        return try {
            val targets = mutableListOf(function).also { it.addAll(findSuperDeclarations(function)) }
            val refs = targets.flatMap { ReferencesSearch.search(it, scope).findAll() }.distinctBy { it.element }
            if (refs.size > MAX_INBOUND_REFS) refs.take(MAX_INBOUND_REFS) else refs
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching references", e)
            null
        }
    }

    private fun createNode(
        function: KtNamedFunction,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        return CallGraphNode(
            id = getFunctionId(function),
            name = function.name ?: "anonymous",
            signature = buildSignature(function),
            fileName = function.containingFile?.name ?: "",
            type = type,
            depth = depth,
            isLibrary = isLibrary,
            loc = function.text?.lines()?.size ?: 0
        ).also { it.psiElement = function }
    }

    /**
     * Returns a stable unique ID for [function].
     * Uses the fully qualified name plus parameter types when available.
     * Falls back to filePath::name for local and anonymous functions that have no fqName.
     */
    private fun getFunctionId(function: KtNamedFunction): String {
        val paramSig = function.valueParameters.joinToString(",") { it.typeReference?.text ?: "Any" }
        val fqName = function.fqName?.asString()
        if (fqName != null) return "$fqName($paramSig)"
        val filePath = function.containingFile?.virtualFile?.path ?: function.containingFile?.name ?: "unknown"
        return "$filePath::${function.name ?: "anonymous"}($paramSig)"
    }

    private fun buildSignature(function: KtNamedFunction): String {
        val params = function.valueParameters.joinToString(", ") { p ->
            "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
        }
        val ret = function.typeReference?.text?.let { ": $it" } ?: ""
        return "${function.name}($params)$ret"
    }

    /** Finds concrete overrides of an abstract/interface function. */
    private fun findImplementations(function: KtNamedFunction): List<KtNamedFunction> {
        if (function.bodyExpression != null) return emptyList()
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            function.toLightMethods().flatMap { lightMethod ->
                OverridingMethodsSearch.search(lightMethod, scope, true).toList()
                    .mapNotNull { it.navigationElement as? KtNamedFunction }
                    .filter { it.bodyExpression != null && isSourceCode(it) }
            }.take(3)
        } catch (_: Exception) { emptyList() }
    }

    /** Finds abstract/interface declarations that this function overrides. */
    private fun findSuperDeclarations(function: KtNamedFunction): List<KtNamedFunction> {
        return try {
            function.toLightMethods().flatMap { lightMethod ->
                SuperMethodsSearch.search(lightMethod, null, true, false).findAll()
                    .mapNotNull { it.method.navigationElement as? KtNamedFunction }
            }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    private fun isSourceCode(function: KtNamedFunction): Boolean {
        val virtualFile = function.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }
}
