package com.mindmap.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
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
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class MindMapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val bridge = JsBridge(browser) { event -> handleEvent(event) }
    private val history = GraphHistory()
    @Volatile private var isDisposed = false
    @Volatile private var currentGraphData: GraphData? = null

    @Volatile private var outboundDepth = 3
    @Volatile private var inboundDepth  = 2
    @Volatile private var retraceOutboundDepth = 1
    @Volatile private var retraceInboundDepth  = 1

    companion object {
        private val LOG = Logger.getInstance(MindMapPanel::class.java)
    }

    init {
        add(browser.component, BorderLayout.CENTER)
        bridge.setup { updateGraph(currentGraphData ?: return@setup) }
        bridge.loadHtml()
    }

    /** Entry point from Alt+G action. */
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
            is JsBridge.MessageEvent.Navigate -> handleNavigate(event.nodeId)
            is JsBridge.MessageEvent.OpenUrl -> BrowserUtil.browse(event.url)
            is JsBridge.MessageEvent.Expand -> handleExpand(event.nodeId)
            is JsBridge.MessageEvent.Trace -> handleTrace(event.nodeId)
            is JsBridge.MessageEvent.HistoryBack -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryBack() }
            is JsBridge.MessageEvent.HistoryForward -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryForward() }
            is JsBridge.MessageEvent.Restart -> SwingUtilities.invokeLater { if (!isDisposed) handleRestart() }
            is JsBridge.MessageEvent.SetDepth -> {
                outboundDepth = event.outbound; inboundDepth = event.inbound
            }
            is JsBridge.MessageEvent.SetRetraceDepth -> {
                retraceOutboundDepth = event.outbound; retraceInboundDepth = event.inbound
            }
        }
    }

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

    private fun handleNavigate(nodeId: String) {
        if (isDisposed) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = runReadAction {
                    val node = currentGraphData?.nodes?.find { it.id == nodeId }
                    val psiElement = node?.psiElement ?: return@runReadAction null
                    if (!psiElement.isValid) return@runReadAction null
                    val virtualFile = psiElement.containingFile?.virtualFile ?: return@runReadAction null
                    val offset = psiElement.textOffset
                    Triple(virtualFile, offset, project)
                } ?: return@executeOnPooledThread

                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    try {
                        val descriptor = OpenFileDescriptor(result.third, result.first, result.second)
                        descriptor.navigate(true)
                        browser.component.requestFocusInWindow()
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating Mindmap...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    val isValid = runReadAction { psiElement.isValid }
                    if (!isValid) return

                    val newGraphData = analyzeElement(psiElement, indicator)
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        pushGraph(newGraphData)
                    }
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Tracing function...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isDisposed) return
                    val isValid = runReadAction { psiElement.isValid }
                    if (!isValid) return

                    val function = psiElement as? KtNamedFunction ?: return
                    val traceData = GraphAnalyzer(project, retraceOutboundDepth, retraceInboundDepth).buildGraph(function, indicator)

                    val current = currentGraphData ?: return

                    // Merge trace results into current graph, deduplicating by ID
                    val existingNodeIds = current.nodes.mapTo(HashSet()) { it.id }
                    val existingEdgeKeys = current.edges.mapTo(HashSet()) { "${it.from}\u0000${it.to}" }
                    val newNodes = current.nodes.toMutableList()
                    val newEdges = current.edges.toMutableList()

                    for (node in traceData.nodes) {
                        if (existingNodeIds.add(node.id)) {
                            newNodes.add(node)
                        }
                    }
                    for (edge in traceData.edges) {
                        val key = "${edge.from}\u0000${edge.to}"
                        if (existingEdgeKeys.add(key)) {
                            newEdges.add(edge)
                        }
                    }

                    val mergedData = GraphData(newNodes, newEdges)

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
        return GraphAnalyzer(project, outboundDepth, inboundDepth).buildGraph(function, indicator)
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
