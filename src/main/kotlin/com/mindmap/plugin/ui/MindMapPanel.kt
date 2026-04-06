package com.mindmap.plugin.ui

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.analysis.GraphData
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Suppress("DEPRECATION")
class MindMapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val gson = GsonBuilder().excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT).create()
    private val jsQuery = JBCefJSQuery.create(browser)
    @Volatile private var isDisposed = false

    @Volatile private var isBrowserReady = false
    private var pendingData: GraphData? = null
    private var currentGraphData: GraphData? = null

    // History for back/forward navigation
    private val history = mutableListOf<GraphData>()
    private var historyIndex = -1

    // Node ID validation: only allow safe characters (alphanumeric, dots, colons, underscores, hyphens)
    private val nodeIdPattern = Regex("^[a-zA-Z0-9._:\\\\\\-<>, ]+$")

    companion object {
        private val LOG = Logger.getInstance(MindMapPanel::class.java)
        private const val MAX_HISTORY_SIZE = 50
        private const val MAX_NODE_ID_LENGTH = 500
    }

    init {
        add(browser.component, BorderLayout.CENTER)
        setupJsBridge()
        loadHtml()
    }

    private fun setupJsBridge() {
        jsQuery.addHandler { request ->
            try {
                handleJsMessage(request)
            } catch (e: Exception) {
                LOG.error("JS bridge handler error", e)
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                try {
                    val bridgeCode = """
                        window.jbCefQuery = function(arg) { ${jsQuery.inject("arg")} };
                    """.trimIndent()
                    browser.executeJavaScript(bridgeCode, browser.url, 0)

                    SwingUtilities.invokeLater {
                        isBrowserReady = true
                        pendingData?.let {
                            updateGraph(it)
                            pendingData = null
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to initialize JS bridge", e)
                }
            }
        }, browser.cefBrowser)
    }

    private fun loadHtml() {
        try {
            val htmlContent = javaClass.getResource("/webview/graph.html")?.readText()
            if (htmlContent != null) {
                browser.loadHTML(htmlContent)
            } else {
                LOG.warn("Could not load graph.html from resources")
            }
        } catch (e: Exception) {
            LOG.error("Failed to load HTML", e)
        }
    }

    /** Called from Alt+G action — resets history and starts fresh */
    fun updateGraph(data: GraphData) {
        currentGraphData = data
        if (!isBrowserReady) {
            pendingData = data
            return
        }

        // Clear history and start fresh
        history.clear()
        history.add(data)
        historyIndex = 0

        sendGraphToJs(data)
        sendHistoryState()
    }

    /** Called from Cmd+Click expand — pushes to history stack */
    private fun pushGraph(data: GraphData) {
        currentGraphData = data
        if (!isBrowserReady) {
            pendingData = data
            return
        }

        // Trim forward history if navigated back, then push
        if (historyIndex < history.size - 1) {
            while (history.size > historyIndex + 1) {
                history.removeAt(history.size - 1)
            }
        }

        // Cap history size to prevent unbounded memory growth
        if (history.size >= MAX_HISTORY_SIZE) {
            history.removeAt(0)
            historyIndex = (historyIndex - 1).coerceAtLeast(0)
        }

        history.add(data)
        historyIndex = history.size - 1

        sendGraphToJs(data)
        sendHistoryState()
    }

    /**
     * Safely sends graph data to JS using Base64 encoding to prevent XSS/injection.
     */
    private fun sendGraphToJs(data: GraphData) {
        if (isDisposed) return
        try {
            val json = gson.toJson(data)
            val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                try {
                    browser.cefBrowser.executeJavaScript(
                        "drawGraph(JSON.parse(atob('$encoded')))",
                        browser.cefBrowser.url, 0
                    )
                } catch (e: Exception) {
                    LOG.error("Failed to send graph to JS", e)
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to encode graph data", e)
        }
    }

    /**
     * Sends merged graph data to JS, preserving existing node positions.
     * Used for trace (double-click) so the view doesn't jump.
     */
    private fun sendMergedGraphToJs(data: GraphData) {
        if (isDisposed) return
        try {
            val json = gson.toJson(data)
            val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                try {
                    browser.cefBrowser.executeJavaScript(
                        "mergeGraph(JSON.parse(atob('$encoded')))",
                        browser.cefBrowser.url, 0
                    )
                } catch (e: Exception) {
                    LOG.error("Failed to send merged graph to JS", e)
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to encode merged graph data", e)
        }
    }

    private fun sendHistoryState() {
        if (isDisposed) return
        val canBack = historyIndex > 0
        val canForward = historyIndex < history.size - 1
        val position = historyIndex + 1
        val total = history.size

        SwingUtilities.invokeLater {
            if (isDisposed) return@invokeLater
            try {
                browser.cefBrowser.executeJavaScript(
                    "updateHistoryState($canBack, $canForward, $position, $total)",
                    browser.cefBrowser.url, 0
                )
            } catch (e: Exception) {
                LOG.error("Failed to send history state", e)
            }
        }
    }

    private fun validateNodeId(nodeId: String?): String? {
        if (nodeId == null) return null
        if (nodeId.length > MAX_NODE_ID_LENGTH) {
            LOG.warn("Node ID exceeds max length: ${nodeId.length}")
            return null
        }
        if (!nodeIdPattern.matches(nodeId)) {
            LOG.warn("Invalid node ID format rejected")
            return null
        }
        return nodeId
    }

    private fun handleJsMessage(request: String) {
        try {
            if (request.length > 2048) {
                LOG.warn("JS message too large: ${request.length} bytes")
                return
            }
            val message = gson.fromJson(request, JsMessage::class.java)
            when (message.type) {
                "navigate" -> handleNavigate(validateNodeId(message.id))
                "expand" -> handleExpand(validateNodeId(message.id))
                "trace" -> handleTrace(validateNodeId(message.id))
                "history_back" -> handleHistoryBack()
                "history_forward" -> handleHistoryForward()
                else -> LOG.debug("Unknown JS message type: ${message.type}")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to handle JS message", e)
        }
    }

    private fun handleNavigate(nodeId: String?) {
        if (nodeId == null) return

        ApplicationManager.getApplication().invokeLater {
            try {
                runReadAction {
                    val node = currentGraphData?.nodes?.find { it.id == nodeId }
                    val psiElement = node?.psiElement ?: return@runReadAction
                    if (!psiElement.isValid) return@runReadAction

                    val virtualFile = psiElement.containingFile?.virtualFile ?: return@runReadAction
                    val offset = psiElement.textOffset
                    val descriptor = OpenFileDescriptor(project, virtualFile, offset)

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                        } catch (e: Exception) {
                            LOG.error("Failed to open editor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to navigate to node", e)
            }
        }
    }

    private fun handleExpand(nodeId: String?) {
        if (nodeId == null) return

        val psiElement = findPsiElement(nodeId) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating Mindmap...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val isValid = runReadAction { psiElement.isValid }
                    if (!isValid) return

                    val analyzer = GraphAnalyzer(project)
                    val newGraphData = analyzer.buildGraph(psiElement, indicator)
                    SwingUtilities.invokeLater {
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

    private fun handleTrace(nodeId: String?) {
        if (nodeId == null) return

        val psiElement = findPsiElement(nodeId) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Tracing function...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val isValid = runReadAction { psiElement.isValid }
                    if (!isValid) return

                    val analyzer = GraphAnalyzer(project)
                    val traceData = analyzer.buildGraph(psiElement, indicator)

                    val current = currentGraphData ?: return

                    // Merge using Set-based dedup for O(1) lookups
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
                        currentGraphData = mergedData
                        if (historyIndex in history.indices) {
                            history[historyIndex] = mergedData
                        }
                        sendMergedGraphToJs(mergedData)
                    }
                } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    LOG.error("Failed to trace node", ex)
                }
            }
        })
    }

    private fun findPsiElement(nodeId: String): org.jetbrains.kotlin.psi.KtNamedFunction? {
        try {
            currentGraphData?.nodes?.find { it.id == nodeId }?.psiElement?.let { return it }
            for (graphData in history.asReversed()) {
                graphData.nodes.find { it.id == nodeId }?.psiElement?.let { return it }
            }
        } catch (e: Exception) {
            LOG.error("Failed to find PSI element for node: $nodeId", e)
        }
        return null
    }

    private fun handleHistoryBack() {
        if (historyIndex > 0) {
            historyIndex--
            val data = history[historyIndex]
            currentGraphData = data
            sendGraphToJs(data)
            sendHistoryState()
        }
    }

    private fun handleHistoryForward() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            val data = history[historyIndex]
            currentGraphData = data
            sendGraphToJs(data)
            sendHistoryState()
        }
    }

    private data class JsMessage(
        val type: String = "",
        val id: String? = null
    )

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            try {
                history.clear()
                currentGraphData = null
                pendingData = null
                jsQuery.dispose()
                browser.dispose()
            } catch (e: Exception) {
                LOG.error("Error during dispose", e)
            }
        }
    }

    override fun removeNotify() {
        try {
            dispose()
        } catch (e: Exception) {
            LOG.error("Error during removeNotify", e)
        }
        super.removeNotify()
    }
}
