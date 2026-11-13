package com.mindmap.plugin.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.mindmap.plugin.analysis.GraphAnalyzer.Companion.MAX_INBOUND_REFS
import com.mindmap.plugin.analysis.GraphAnalyzer.Companion.MAX_NODES

/**
 * Language-neutral orchestrator for call-graph construction.
 *
 * # Architecture role
 *
 * `GraphAnalyzer` is the **backend orchestrator**: it walks callables breadth-first,
 * enforces the shared size/depth limits, deduplicates nodes + edges, and produces a
 * [GraphData] the frontend can render. It knows nothing about Kotlin, Java, Python, or
 * any future language — every PSI operation is delegated to a [CallableProvider]
 * resolved via the `com.mindmap.plugin.callableProvider` extension point.
 *
 * **This file must contain zero language-specific imports.** A `grep` for
 * `org.jetbrains.kotlin`, `PsiMethod`, or any `Py*` / `Go*` symbol inside this file is
 * a bug. If you need something language-specific, extend [CallableProvider] instead.
 *
 * # What stays here (language-free)
 * - Node / edge limits ([MAX_NODES], [MAX_INBOUND_REFS])
 * - Depth bookkeeping (`maxOutboundDepth`, `maxInboundDepth`)
 * - Traversal control (breadth-first-ish; reuses existing `ReadAction` envelope)
 * - Edge deduplication ([edgeKeys])
 * - Library vs. project source classification ([isSourceCode], via `ProjectFileIndex`)
 * - Abstract → concrete expansion via [CallableProvider.implementations]
 * - Progress indicator / cancellation
 * - Test-filter dispatch to [CallableProvider.isTestFunction]
 *
 * # What lives in providers (language-specific)
 * - Finding the enclosing callable at a cursor position
 * - Walking call expressions inside a body
 * - Reference searching
 * - Test-annotation detection
 * - Light-method bridges, FQN resolution, `analyze {}` sessions, etc.
 */
class GraphAnalyzer(
    private val project: Project,
    private val maxOutboundDepth: Int = 3,
    private val maxInboundDepth: Int = 2,
    private val debugMode: Boolean = false,
    private val excludeTests: Boolean = true
) {

    companion object {
        private val LOG = Logger.getInstance(GraphAnalyzer::class.java)

        // Hard cap on graph size. Shared across all languages and all depth settings.
        private const val MAX_NODES = 300

        // Inbound-reference cap per target. Widely-used functions can have thousands of
        // callers; capping here keeps the search bounded without hurting typical cases.
        private const val MAX_INBOUND_REFS = 30
    }

    private fun debug(msg: () -> String) {
        if (debugMode) com.mindmap.plugin.DebugLog.log(msg())
    }

    /**
     * Builds the full call graph for [root]. Must be called on a background thread.
     *
     * The entire traversal is wrapped in a single [ReadAction] — providers may assume
     * PSI reads are safe without starting their own read actions. Cancellation through
     * [indicator] works at two granularities: the outer read action checks each
     * iteration, and outbound/inbound inner loops call `indicator?.checkCanceled()`
     * on every step.
     */
    fun buildGraph(root: CallableFunction, indicator: ProgressIndicator? = null): GraphData {
        val nodes = mutableMapOf<String, CallGraphNode>()
        val edges = mutableListOf<CallGraphEdge>()
        val edgeKeys = HashSet<String>()
        val startTime = System.currentTimeMillis()

        debug {
            "Building call graph for '${root.name}' in ${root.containingFile?.name} — " +
                    "outbound depth: $maxOutboundDepth, inbound depth: $maxInboundDepth, lang: ${root.languageId}"
        }

        try {
            ReadAction.run<RuntimeException> {
                val rootId = getCallableId(root)
                nodes[rootId] = createNode(root, NodeType.ROOT, 0)

                indicator?.text = "Analyzing outbound calls..."
                if (root.hasBody) {
                    analyzeOutbound(root, nodes, edges, edgeKeys, 1, indicator)
                } else {
                    // Abstract/interface root → expand to concrete implementations.
                    debug { "Root has no body — searching for concrete implementations" }
                    val provider = providerFor(root) ?: return@run
                    for (impl in provider.implementations(project, root)) {
                        if (nodes.size >= MAX_NODES) break
                        val implId = getCallableId(impl)
                        if (!nodes.containsKey(implId)) nodes[implId] =
                            createNode(impl, NodeType.OUTBOUND, 1)
                        addEdgeIfNew(rootId, implId, nodes, edges, edgeKeys)
                        analyzeOutbound(impl, nodes, edges, edgeKeys, 2, indicator)
                    }
                }
                debug { "Outbound done — ${nodes.size} nodes so far" }

                indicator?.text = "Finding callers..."
                analyzeInbound(root, nodes, edges, edgeKeys, 1, indicator)
                debug { "Inbound done — total nodes: ${nodes.size}" }
            }
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.error("Error building graph", e)
            com.mindmap.plugin.DebugLog.error("Error building graph", e)
        }

        debug {
            "Graph build complete in ${System.currentTimeMillis() - startTime}ms — " +
                    "${nodes.size} nodes, ${edges.size} edges"
        }
        return GraphData(nodes.values.toList(), edges)
    }

    /**
     * Walks every registered provider asking each to find the nearest enclosing callable
     * for [element]. First non-null wins — providers are expected to be mutually
     * exclusive (each owns its own language's PSI types).
     */
    fun findCallable(element: PsiElement): CallableFunction? {
        for (provider in CallableProvider.EP_NAME.extensionList) {
            try {
                val found = provider.callableAt(element)
                if (found != null) return found
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (e: Exception) {
                LOG.debug("Provider ${provider.languageId} failed on callableAt", e)
            }
        }
        return null
    }

    private fun providerFor(callable: CallableFunction): CallableProvider? {
        return CallableProvider.EP_NAME.extensionList.firstOrNull { it.supports(callable) }
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
        callable: CallableFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > maxOutboundDepth || nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val provider = providerFor(callable) ?: return
        val callerId = getCallableId(callable)

        for (target in provider.outboundTargets(project, callable)) {
            if (nodes.size >= MAX_NODES) break
            processOutboundTarget(callerId, target, nodes, edges, edgeKeys, currentDepth, indicator)
        }
    }

    /**
     * Adds [target] to the graph and recurses if it's a project (non-library) function.
     * Abstract/interface targets expand through [CallableProvider.implementations].
     */
    private fun processOutboundTarget(
        callerId: String,
        target: CallableFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator?
    ) {
        val provider = providerFor(target) ?: return
        if (excludeTests && provider.isTestFunction(target)) return

        val targetId = getCallableId(target)
        val isLibrary = !isSourceCode(target)
        if (!nodes.containsKey(targetId)) nodes[targetId] =
            createNode(target, NodeType.OUTBOUND, currentDepth, isLibrary)
        addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

        if (!isLibrary && currentDepth < maxOutboundDepth) {
            if (target.hasBody) {
                analyzeOutbound(target, nodes, edges, edgeKeys, currentDepth + 1, indicator)
            } else {
                expandAbstractOutbound(
                    targetId,
                    target,
                    nodes,
                    edges,
                    edgeKeys,
                    currentDepth,
                    indicator
                )
            }
        }
    }

    private fun expandAbstractOutbound(
        targetId: String,
        target: CallableFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator?
    ) {
        val provider = providerFor(target) ?: return
        for (impl in provider.implementations(project, target)) {
            if (nodes.size >= MAX_NODES) break
            val implId = getCallableId(impl)
            if (!nodes.containsKey(implId)) nodes[implId] =
                createNode(impl, NodeType.OUTBOUND, currentDepth + 1)
            addEdgeIfNew(targetId, implId, nodes, edges, edgeKeys)
            if (currentDepth + 1 < maxOutboundDepth) {
                analyzeOutbound(impl, nodes, edges, edgeKeys, currentDepth + 2, indicator)
            }
        }
    }

    private fun analyzeInbound(
        callable: CallableFunction,
        nodes: MutableMap<String, CallGraphNode>,
        edges: MutableList<CallGraphEdge>,
        edgeKeys: HashSet<String>,
        currentDepth: Int,
        indicator: ProgressIndicator? = null
    ) {
        if (currentDepth > maxInboundDepth || nodes.size >= MAX_NODES) return
        indicator?.checkCanceled()

        val provider = providerFor(callable) ?: return
        val targetId = getCallableId(callable)
        val scope = GlobalSearchScope.projectScope(project)

        val callers = try {
            val all = provider.inboundCallers(project, callable, scope)
            if (all.size > MAX_INBOUND_REFS) all.take(MAX_INBOUND_REFS) else all
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Provider ${provider.languageId} failed inbound search", e)
            return
        }

        for (caller in callers) {
            if (nodes.size >= MAX_NODES) break
            val callerProvider = providerFor(caller) ?: continue
            if (excludeTests && callerProvider.isTestFunction(caller)) continue

            val callerId = getCallableId(caller)
            val isLibrary = !isSourceCode(caller)

            if (!nodes.containsKey(callerId)) nodes[callerId] =
                createNode(caller, NodeType.INBOUND, currentDepth, isLibrary)
            addEdgeIfNew(callerId, targetId, nodes, edges, edgeKeys)

            if (!isLibrary && currentDepth < maxInboundDepth) {
                analyzeInbound(caller, nodes, edges, edgeKeys, currentDepth + 1, indicator)
            }
        }
    }

    private fun createNode(
        callable: CallableFunction,
        type: NodeType,
        depth: Int,
        isLibrary: Boolean = false
    ): CallGraphNode {
        return CallGraphNode(
            id = getCallableId(callable),
            name = callable.name ?: "anonymous",
            signature = callable.signatureDisplay,
            fileName = callable.containingFile?.name ?: "",
            language = callable.languageId,
            type = type,
            depth = depth,
            isLibrary = isLibrary,
            loc = callable.lineCount,
            qualifiedName = callable.qualifiedName ?: (callable.name ?: "anonymous"),
            isNameAmbiguous = callable.isNameAmbiguous
        ).also { it.psiElement = callable }
    }

    /**
     * Stable unique id across languages. Prefers the FQN + parameter-type signature when
     * available; falls back to `filePath::name` for local / anonymous callables without
     * an FQN.
     *
     * Both FQN and parameter signature come from the [CallableFunction] itself — no
     * language-specific `when` branch lives here, so adding a new language does not
     * force any edit to this file.
     */
    private fun getCallableId(callable: CallableFunction): String {
        val paramSig = callable.parameterSignature
        val fq = callable.fqName
        if (!fq.isNullOrEmpty()) return "$fq($paramSig)"
        val filePath = callable.containingFile?.virtualFile?.path
            ?: callable.containingFile?.name
            ?: "unknown"
        return "$filePath::${callable.name ?: "anonymous"}($paramSig)"
    }

    /** Language-neutral project-vs-library classification via IntelliJ's file index. */
    private fun isSourceCode(callable: CallableFunction): Boolean {
        val virtualFile = callable.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)
    }
}
