package com.mindmap.plugin.ui

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.mindmap.plugin.analysis.GraphData
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.SwingUtilities

/**
 * Handles all JS<->Kotlin communication over JCEF.
 *
 * Receives JSON messages from the browser via [JBCefJSQuery], parses and validates them
 * into typed [MessageEvent] objects, then forwards them to [onMessage].
 * Sends graph data to the browser by serializing to JSON, Base64-encoding, then calling
 * a JS function with the decoded object. Base64 prevents XSS from node names that
 * contain quotes or script tags.
 */
class JsBridge(
    private val browser: JBCefBrowser,
    private val onMessage: (MessageEvent) -> Unit
) {

    private val gson = GsonBuilder().excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT).create()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    @Volatile private var isDisposed = false
    @Volatile private var isBrowserReady = false
    // @Volatile: written from background threads (markPending), read from CEF thread (onLoadEnd).
    @Volatile private var pendingData: GraphData? = null
    @Volatile private var pendingAction: (() -> Unit)? = null
    // Stored so it can be removed in dispose() to prevent post-dispose callbacks and memory leaks.
    private var loadHandler: CefLoadHandlerAdapter? = null

    companion object {
        private val LOG = Logger.getInstance(JsBridge::class.java)
        private const val MAX_MESSAGE_SIZE = 2048
        private const val MAX_NODE_ID_LENGTH = 500
        private val NODE_ID_PATTERN = Regex("^[a-zA-Z0-9._:\\\\\\-<>,?*@\\[\\] ()]+$")
        private val ALLOWED_SCHEMES = setOf("http", "https")
    }

    /** Typed representation of each incoming JS message type. */
    sealed class MessageEvent {
        /** User clicked a node: navigate editor to that function. */
        data class Navigate(val nodeId: String) : MessageEvent()
        /** User clicked an external link (e.g. help link in the JCEF error panel). */
        data class OpenUrl(val url: String) : MessageEvent()
        /** User expanded a node: rebuild graph centered on that function. */
        data class Expand(val nodeId: String) : MessageEvent()
        /** User triggered trace: overlay callers/callees of that node on the current graph. */
        data class Trace(val nodeId: String) : MessageEvent()
        data object HistoryBack : MessageEvent()
        data object HistoryForward : MessageEvent()
        /** User restarted: clear graph and history. */
        data object Restart : MessageEvent()
        /** User changed depth sliders for normal graph generation. */
        data class SetDepth(val outbound: Int, val inbound: Int) : MessageEvent()
        /** User changed depth sliders specifically for trace operations. */
        data class SetRetraceDepth(val outbound: Int, val inbound: Int) : MessageEvent()
        /** User toggled debug mode in settings. */
        data class SetDebug(val enabled: Boolean) : MessageEvent()
    }

    /**
     * Registers the JS query handler and installs a load handler that injects the bridge
     * function into the page on every load. The bridge code must be injected in [onLoadEnd]
     * because the page context is not available before that point.
     * [onReady] is called once the page is ready and a pending graph is waiting to be drawn.
     */
    fun setup(onReady: () -> Unit) {
        jsQuery.addHandler { request ->
            try {
                handleRawMessage(request)
            } catch (e: Exception) {
                LOG.error("JS bridge handler error", e)
            }
            null
        }

        loadHandler = object : CefLoadHandlerAdapter() {
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
        }
        browser.jbCefClient.addLoadHandler(loadHandler!!, browser.cefBrowser)
    }

    fun loadHtml() {
        // Read the 124KB resource on a pooled thread to avoid blocking the EDT at startup.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val htmlContent = javaClass.getResource("/webview/graph.html")?.readText()
                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    if (htmlContent != null) {
                        browser.loadHTML(htmlContent)
                    } else {
                        LOG.warn("Could not load graph.html from resources")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to load HTML", e)
            }
        }
    }

    val isReady: Boolean get() = isBrowserReady

    fun markPending(data: GraphData) {
        pendingData = data
    }

    fun sendGraph(data: GraphData) {
        executeWithEncodedData(data, "drawGraph")
    }

    /** Preserves existing node positions (used by trace merge). */
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

    // JSON serialization and Base64 encoding run on a pooled thread so the EDT is never
    // blocked by data processing — only the lightweight JS dispatch hits the EDT.
    private fun executeWithEncodedData(data: GraphData, jsFunction: String) {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
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
            "set_debug" -> MessageEvent.SetDebug(msg.enabled ?: false)
            else -> {
                LOG.debug("Unknown JS message type: ${msg.type}")
                null
            }
        }
    }

    // Defense-in-depth: node IDs come from the browser, so we validate length and charset
    // before using them to look up PSI elements. An overly long or script-like ID is rejected.
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
        val inbound: Int? = null,
        val enabled: Boolean? = null
    )

    fun dispose() {
        isDisposed = true
        pendingData = null
        pendingAction = null
        try {
            loadHandler?.let { browser.jbCefClient.removeLoadHandler(it, browser.cefBrowser) }
            loadHandler = null
            jsQuery.dispose()
        } catch (e: Exception) {
            LOG.error("Error disposing JsBridge", e)
        }
    }
}
