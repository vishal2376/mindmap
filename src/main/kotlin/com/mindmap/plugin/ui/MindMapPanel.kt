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
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.analysis.GraphData
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
        currentGraphData = data
        if (!bridge.isReady) {
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
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = ReadAction.compute<Triple<VirtualFile, Int, Project>?, RuntimeException> {
                    val psiElement = currentGraphData?.nodes?.find { it.id == nodeId }?.psiElement ?: return@compute null
                    if (!psiElement.isValid) return@compute null
                    val virtualFile = psiElement.containingFile?.virtualFile ?: return@compute null
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
        val psiElement = history.findPsiElement(nodeId) ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Mindmap...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    if (!ReadAction.compute<Boolean, RuntimeException> { psiElement.isValid }) return
                    val newData = analyzeElement(psiElement, indicator)
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
        val psiElement = history.findPsiElement(nodeId) ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Tracing function...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    if (!ReadAction.compute<Boolean, RuntimeException> { psiElement.isValid }) return
                    val function = psiElement as? KtNamedFunction ?: return
                    val d = depths
                    val traceData = GraphAnalyzer(project, d.retraceOutbound, d.retraceInbound).buildGraph(function, indicator)
                    val mergedData = (currentGraphData ?: return).merge(traceData)
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
        return GraphAnalyzer(project, d.outbound, d.inbound).buildGraph(function, indicator)
    }

    private fun handleHistoryBack() {
        val data = history.back() ?: return
        currentGraphData = data
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun handleHistoryForward() {
        val data = history.forward() ?: return
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
