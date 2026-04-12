package com.mindmap.plugin.ui

import com.google.gson.GsonBuilder
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.mindmap.plugin.analysis.GraphData
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.SwingUtilities

/**
 * Handles all JS<->Kotlin communication over JCEF.
 * Owns the JBCefJSQuery, message parsing, validation, and graph data injection.
 */
@Suppress("DEPRECATION")
class JsBridge(
    private val browser: JBCefBrowser,
    private val onMessage: (MessageEvent) -> Unit
) {

    private val gson = GsonBuilder().excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT).create()
    private val jsQuery = JBCefJSQuery.create(browser)
    @Volatile private var isDisposed = false
    @Volatile private var isBrowserReady = false
    private var pendingData: GraphData? = null
    private var pendingAction: (() -> Unit)? = null

    companion object {
        private val LOG = Logger.getInstance(JsBridge::class.java)
        private const val MAX_MESSAGE_SIZE = 2048
        private const val MAX_NODE_ID_LENGTH = 500
        private val NODE_ID_PATTERN = Regex("^[a-zA-Z0-9._:\\\\\\-<>,?*@\\[\\] ()]+$")
        private val ALLOWED_SCHEMES = setOf("http", "https")
    }

    /** Parsed message from the JS frontend */
    sealed class MessageEvent {
        data class Navigate(val nodeId: String) : MessageEvent()
        data class OpenUrl(val url: String) : MessageEvent()
        data class Expand(val nodeId: String) : MessageEvent()
        data class Trace(val nodeId: String) : MessageEvent()
        data object HistoryBack : MessageEvent()
        data object HistoryForward : MessageEvent()
        data object Restart : MessageEvent()
        data class SetDepth(val outbound: Int, val inbound: Int) : MessageEvent()
        data class SetRetraceDepth(val outbound: Int, val inbound: Int) : MessageEvent()
    }

    fun setup(onReady: () -> Unit) {
        jsQuery.addHandler { request ->
            try {
                handleRawMessage(request)
            } catch (e: Exception) {
                LOG.error("JS bridge handler error", e)
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                try {
                    val bridgeCode = """
                        window.jbCefQuery = function(arg) { ${jsQuery.inject("arg")} };
                    """.trimIndent()
                    cefBrowser.executeJavaScript(bridgeCode, cefBrowser.url, 0)

                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        isBrowserReady = true
                        pendingAction?.invoke()
                        pendingAction = null
                        pendingData?.let {
                            onReady()
                            pendingData = null
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to initialize JS bridge", e)
                }
            }
        }, browser.cefBrowser)
    }

    fun loadHtml() {
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

    val isReady: Boolean get() = isBrowserReady

    fun markPending(data: GraphData) {
        pendingData = data
    }

    /** Sends full graph data (replaces current view) */
    fun sendGraph(data: GraphData) {
        executeWithEncodedData(data, "drawGraph")
    }

    /** Sends merged graph data (preserves existing positions) */
    fun sendMergedGraph(data: GraphData) {
        executeWithEncodedData(data, "mergeGraph")
    }

    fun sendHistoryState(canBack: Boolean, canForward: Boolean, position: Int, total: Int) {
        if (isDisposed) return
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

    private fun executeWithEncodedData(data: GraphData, jsFunction: String) {
        if (isDisposed) return
        try {
            val json = gson.toJson(data)
            val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                try {
                    browser.cefBrowser.executeJavaScript(
                        "$jsFunction(JSON.parse(atob('$encoded')))",
                        browser.cefBrowser.url, 0
                    )
                } catch (e: Exception) {
                    LOG.error("Failed to execute $jsFunction", e)
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to encode graph data for $jsFunction", e)
        }
    }

    private fun handleRawMessage(request: String) {
        if (request.length > MAX_MESSAGE_SIZE) {
            LOG.warn("JS message too large: ${request.length} bytes")
            return
        }
        try {
            val msg = gson.fromJson(request, RawJsMessage::class.java)
            val event = parseMessage(msg) ?: return
            onMessage(event)
        } catch (e: Exception) {
            LOG.warn("Failed to handle JS message", e)
        }
    }

    private fun parseMessage(msg: RawJsMessage): MessageEvent? {
        return when (msg.type) {
            "navigate" -> {
                if (msg.url != null) {
                    validateUrl(msg.url)?.let { MessageEvent.OpenUrl(it) }
                } else {
                    validateNodeId(msg.id)?.let { MessageEvent.Navigate(it) }
                }
            }
            "expand" -> validateNodeId(msg.id)?.let { MessageEvent.Expand(it) }
            "trace" -> validateNodeId(msg.id)?.let { MessageEvent.Trace(it) }
            "history_back" -> MessageEvent.HistoryBack
            "history_forward" -> MessageEvent.HistoryForward
            "restart" -> MessageEvent.Restart
            "set_depth" -> {
                val out = msg.outbound?.coerceIn(1, 5) ?: return null
                val inn = msg.inbound?.coerceIn(1, 5) ?: return null
                MessageEvent.SetDepth(out, inn)
            }
            "set_retrace_depth" -> {
                val out = msg.outbound?.coerceIn(1, 5) ?: return null
                val inn = msg.inbound?.coerceIn(1, 5) ?: return null
                MessageEvent.SetRetraceDepth(out, inn)
            }
            else -> {
                LOG.debug("Unknown JS message type: ${msg.type}")
                null
            }
        }
    }

    private fun validateNodeId(nodeId: String?): String? {
        if (nodeId == null) return null
        if (nodeId.length > MAX_NODE_ID_LENGTH) {
            LOG.warn("Node ID exceeds max length: ${nodeId.length}")
            return null
        }
        if (!NODE_ID_PATTERN.matches(nodeId)) {
            LOG.warn("Invalid node ID format rejected")
            return null
        }
        return nodeId
    }

    private fun validateUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            if (uri.scheme?.lowercase() in ALLOWED_SCHEMES) url
            else {
                LOG.warn("Blocked URL with disallowed scheme: ${uri.scheme}")
                null
            }
        } catch (e: Exception) {
            LOG.warn("Invalid URL rejected: $url", e)
            null
        }
    }

    private data class RawJsMessage(
        val type: String = "",
        val id: String? = null,
        val url: String? = null,
        val outbound: Int? = null,
        val inbound: Int? = null
    )

    fun dispose() {
        isDisposed = true
        pendingData = null
        pendingAction = null
        try {
            jsQuery.dispose()
        } catch (e: Exception) {
            LOG.error("Error disposing JsBridge", e)
        }
    }
}
