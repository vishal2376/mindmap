package com.mindmap.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.psi.PsiElement
import com.intellij.ui.jcef.JBCefBrowser
import com.mindmap.plugin.DebugLog
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.analysis.GraphData
import com.mindmap.plugin.analysis.NodeType
import com.mindmap.plugin.analysis.merge
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Hosts the JCEF browser and coordinates the call graph lifecycle.
 *
 * Owns three stateful components: [browser] (renders graph.html), [bridge] (JS<->Kotlin IPC),
 * and [history] (back/forward navigation). Graph analysis runs on background threads via
 * [ProgressManager]; all UI and history mutations happen on the EDT.
 */
class MindMapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val bridge = JsBridge(browser) { event -> handleEvent(event) }
    private val history = GraphHistory()
    @Volatile private var isDisposed = false
    @Volatile private var currentGraphData: GraphData? = null
    @Volatile private var debugMode = false

    // A single volatile reference to an immutable snapshot ensures background threads always
    // read outbound+inbound as a consistent pair. Separate @Volatile fields would let a
    // background thread read a new outbound value alongside a stale inbound value.
    private data class DepthSettings(
        val outbound: Int = 3,
        val inbound: Int = 2,
        val retraceOutbound: Int = 1,
        val retraceInbound: Int = 1
    )
    @Volatile private var depths = DepthSettings()

    companion object {
        private val LOG = Logger.getInstance(MindMapPanel::class.java)
    }

    private fun debug(msg: () -> String) { if (debugMode) DebugLog.log(msg()) }

    init {
        add(browser.component, BorderLayout.CENTER)
        bridge.setup { updateGraph(currentGraphData ?: return@setup) }
        bridge.loadHtml()
    }

    /**
     * Entry point from Alt+G. Resets history and renders [data] as the new root graph.
     * Use [pushGraph] instead when navigating within an existing session (expand/re-center).
     */
    fun updateGraph(data: GraphData) {
        debug { "updateGraph: ${data.nodes.size} nodes, ${data.edges.size} edges, root=${data.nodes.find { it.type == NodeType.ROOT }?.name}" }
        currentGraphData = data
        if (!bridge.isReady) {
            debug { "updateGraph: bridge not ready, marking pending" }
            bridge.markPending(data)
            return
        }
        history.reset(data)
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun handleEvent(event: JsBridge.MessageEvent) {
        when (event) {
            is JsBridge.MessageEvent.Navigate    -> handleNavigate(event.nodeId)
            is JsBridge.MessageEvent.OpenUrl     -> BrowserUtil.browse(event.url)
            is JsBridge.MessageEvent.Expand      -> handleExpand(event.nodeId)
            is JsBridge.MessageEvent.Trace       -> handleTrace(event.nodeId)
            is JsBridge.MessageEvent.HistoryBack -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryBack() }
            is JsBridge.MessageEvent.HistoryForward -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryForward() }
            is JsBridge.MessageEvent.Restart     -> SwingUtilities.invokeLater { if (!isDisposed) handleRestart() }
            is JsBridge.MessageEvent.SetDepth    -> depths = depths.copy(outbound = event.outbound, inbound = event.inbound)
            is JsBridge.MessageEvent.SetRetraceDepth -> depths = depths.copy(retraceOutbound = event.outbound, retraceInbound = event.inbound)
            is JsBridge.MessageEvent.SetDebug -> {
                debugMode = event.enabled
                DebugLog.enabled = event.enabled
                if (event.enabled) DebugLog.log("Debug mode enabled — log file: ${DebugLog.logFilePath}")
                else DebugLog.log("Debug mode disabled")
            }
        }
    }

    // Pushes a new graph onto the history stack (used by expand/re-center).
    private fun pushGraph(data: GraphData) {
        currentGraphData = data
        if (!bridge.isReady) {
            bridge.markPending(data)
            return
        }
        history.push(data)
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun sendHistoryState() {
        if (isDisposed) return
        bridge.sendHistoryState(history.canBack, history.canForward, history.position, history.total)
    }

    private fun handleRestart() {
        history.clear()
        currentGraphData = null
        sendHistoryState()
    }

    // PSI reads must not block the EDT, so we dispatch to a pooled thread first.
    // Once we have the virtual file and offset, we hand off to the EDT for actual navigation.
    private fun handleNavigate(nodeId: String) {
        if (isDisposed) return
        debug { "navigate: nodeId=$nodeId" }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = ReadAction.compute<Triple<VirtualFile, Int, Project>?, RuntimeException> {
                    val node = currentGraphData?.nodes?.find { it.id == nodeId }
                    val psiElement = node?.psiElement
                    if (node == null) { debug { "navigate: node not found in graph data" }; return@compute null }
                    if (psiElement == null) { debug { "navigate: psiElement is null for ${node.name}" }; return@compute null }
                    if (!psiElement.isValid) { debug { "navigate: psiElement invalid for ${node.name}" }; return@compute null }
                    val virtualFile = psiElement.containingFile?.virtualFile
                    if (virtualFile == null) { debug { "navigate: virtualFile is null for ${node.name}" }; return@compute null }
                    debug { "navigate: → ${virtualFile.name}:${psiElement.textOffset} (${node.name})" }
                    Triple(virtualFile, psiElement.textOffset, project)
                } ?: return@executeOnPooledThread

                // WriteIntentReadAction required: newer IntelliJ/Android Studio enforces
                // read access for editor model operations (caret movement) on EDT.
                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    try {
                        WriteIntentReadAction.run {
                            OpenFileDescriptor(result.third, result.first, result.second).navigate(true)
                        }
                    } catch (e: Exception) {
                        LOG.error("Failed to open editor", e)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to navigate to node", e)
            }
        }
    }

    private fun handleExpand(nodeId: String) {
        if (isDisposed) return
        debug { "expand: nodeId=$nodeId" }
        val psiElement = history.findPsiElement(nodeId)
        if (psiElement == null) { debug { "expand: PSI element not found in history" }; return }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Mindmap...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    if (!ReadAction.compute<Boolean, RuntimeException> { psiElement.isValid }) {
                        debug { "expand: psiElement no longer valid" }; return
                    }
                    val startTime = System.currentTimeMillis()
                    val newData = analyzeElement(psiElement, indicator)
                    debug { "expand: built graph in ${System.currentTimeMillis() - startTime}ms — ${newData.nodes.size} nodes, ${newData.edges.size} edges" }
                    SwingUtilities.invokeLater { if (!isDisposed) pushGraph(newData) }
                } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    LOG.error("Failed to expand node", ex)
                }
            }
        })
    }

    private fun handleTrace(nodeId: String) {
        if (isDisposed) return
        debug { "trace: nodeId=$nodeId" }
        val psiElement = history.findPsiElement(nodeId)
        if (psiElement == null) { debug { "trace: PSI element not found in history" }; return }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Tracing function...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    if (!ReadAction.compute<Boolean, RuntimeException> { psiElement.isValid }) {
                        debug { "trace: psiElement no longer valid" }; return
                    }
                    val function = psiElement as? KtNamedFunction ?: return
                    val d = depths
                    debug { "trace: ${function.name} with depth out=${d.retraceOutbound} in=${d.retraceInbound}" }
                    val startTime = System.currentTimeMillis()
                    val traceData = GraphAnalyzer(project, d.retraceOutbound, d.retraceInbound, debugMode).buildGraph(function, indicator)
                    val mergedData = (currentGraphData ?: return).merge(traceData)
                    debug { "trace: done in ${System.currentTimeMillis() - startTime}ms — merged to ${mergedData.nodes.size} nodes, ${mergedData.edges.size} edges" }
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        currentGraphData = mergedData
                        history.updateCurrent(mergedData)
                        bridge.sendMergedGraph(mergedData)
                    }
                } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    LOG.error("Failed to trace node", ex)
                }
            }
        })
    }

    private fun analyzeElement(element: PsiElement, indicator: ProgressIndicator): GraphData {
        val function = element as? KtNamedFunction
            ?: throw IllegalArgumentException("Element is not a KtNamedFunction: ${element.javaClass.simpleName}")
        val d = depths
        return GraphAnalyzer(project, d.outbound, d.inbound, debugMode).buildGraph(function, indicator)
    }

    private fun handleHistoryBack() {
        val data = history.back() ?: return
        debug { "history back: position ${history.position}/${history.total}, ${data.nodes.size} nodes" }
        currentGraphData = data
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun handleHistoryForward() {
        val data = history.forward() ?: return
        debug { "history forward: position ${history.position}/${history.total}, ${data.nodes.size} nodes" }
        currentGraphData = data
        bridge.sendGraph(data)
        sendHistoryState()
    }

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            try {
                history.clear()
                currentGraphData = null
                bridge.dispose()
                browser.dispose()
            } catch (e: Exception) {
                LOG.error("Error during dispose", e)
            }
        }
    }
}
