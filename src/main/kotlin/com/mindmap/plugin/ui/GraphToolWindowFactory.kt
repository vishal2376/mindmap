package com.mindmap.plugin.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.mindmap.plugin.DebugLog
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Creates the Mindmap tool window content.
 *
 * When JCEF is available, creates a [MindMapPanel] that renders the call graph.
 * When JCEF is unavailable (common in Android Studio or non-JBR runtime setups),
 * shows a styled error panel with instructions to switch to a JBR runtime.
 */
class GraphToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        // Catppuccin Mocha palette
        private val BG = Color(30, 30, 46)
        private val SURFACE1 = Color(49, 50, 68)
        private val SURFACE2 = Color(88, 91, 112)
        private val RED = Color(243, 139, 168)
        private val BLUE = Color(137, 180, 250)

        private val LOG = Logger.getInstance(GraphToolWindowFactory::class.java)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setToHideOnEmptyContent(true)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Checked via reflection, not `JBCefApp.isSupported()` directly: merely naming a
        // JCEF type forces the JVM to resolve it, which throws NoClassDefFoundError before
        // this method's body even runs on IDEs where the "Web Browser (JCEF)" plugin isn't
        // on our class loader (build 262+ ships JCEF as a separate bundled plugin; see
        // the optional com.intellij.modules.jcef <depends> in plugin.xml).
        if (!JcefSupport.isClassLoadable()) {
            val content = ContentFactory.getInstance()
                .createContent(createJcefErrorPanel(), "Call Graph", false)
            toolWindow.contentManager.addContent(content)
            return
        }
        // Without this try/catch, any other exception thrown while building the panel
        // (resource loading, etc.) propagates out of the platform's content-creation call,
        // no content ever gets added, and the tool window is left showing the generic
        // IntelliJ "Nothing to show" placeholder with zero indication anything went wrong
        // (#22). Always fall back to a visible, actionable error panel instead.
        try {
            if (!JBCefApp.isSupported()) {
                val content = ContentFactory.getInstance()
                    .createContent(createJcefErrorPanel(), "Call Graph", false)
                toolWindow.contentManager.addContent(content)
                return
            }
            val panel = MindMapPanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "Call Graph", false)
            content.setDisposer(panel)
            toolWindow.contentManager.addContent(content)
        } catch (t: Throwable) {
            LOG.error("Failed to create Mindmap tool window content", t)
            DebugLog.error("Failed to create Mindmap tool window content", t)
            val content = ContentFactory.getInstance()
                .createContent(createCrashErrorPanel(t), "Call Graph", false)
            toolWindow.contentManager.addContent(content)
        }
    }

    private fun createJcefErrorPanel(): JPanel =
        buildErrorPanel(
            "JCEF Not Available",
            jcefInstructionsHtml(),
            "Web Browser (JCEF) plugin" to "https://plugins.jetbrains.com/plugin/31360-web-browser-jcef-"
        )

    private fun createCrashErrorPanel(t: Throwable): JPanel =
        buildErrorPanel("Failed to Load Mindmap", crashInstructionsHtml(t))

    private fun buildErrorPanel(
        title: String,
        bodyHtml: String,
        extraLink: Pair<String, String>? = null
    ): JPanel {
        val outer = JPanel(GridBagLayout()).apply { background = BG }
        val card = buildInnerCard()
        card.add(JLabel(title).apply {
            foreground = RED; font = Font("SansSerif", Font.BOLD, 16); alignmentX =
            Component.CENTER_ALIGNMENT
        })
        card.add(Box.createVerticalStrut(12))
        card.add(JLabel(bodyHtml).apply { alignmentX = Component.CENTER_ALIGNMENT })
        card.add(Box.createVerticalStrut(16))
        extraLink?.let { (text, url) ->
            card.add(com.intellij.ui.components.BrowserLink(text, url).apply {
                alignmentX = Component.CENTER_ALIGNMENT; foreground = BLUE
            })
            card.add(Box.createVerticalStrut(8))
        }
        card.add(
            com.intellij.ui.components.BrowserLink(
                "Not working? Checkout help",
                "https://vishal2376.github.io/mindmap#troubleshoot"
            ).apply {
                alignmentX = Component.CENTER_ALIGNMENT; foreground = BLUE
            })
        outer.add(card)
        return outer
    }

    /** Rounded card panel with a Catppuccin surface background and a 1px border. */
    private fun buildInnerCard(): JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = SURFACE1
            g2.fillRoundRect(0, 0, width, height, 16, 16)
            g2.color = SURFACE2
            g2.stroke = BasicStroke(1.0f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
            g2.dispose()
        }
    }.apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(28, 36, 28, 36)
    }

    private fun jcefInstructionsHtml(): String =
        "<html><div style='text-align:center;width:260px;color:#cdd6f4;font-family:sans-serif;font-size:12px;line-height:1.6;'>" +
                "Mindmap requires <b style='color:#bac2de;'>Chromium (JCEF)</b>.<br><br>" +
                "<div style='text-align:left;display:inline-block;'>" +
                "<b style='color:#89b4fa;'>1.</b> Go to <b>Settings → Plugins → Marketplace</b><br>" +
                "<b style='color:#89b4fa;'>2.</b> Search: <i>\"Web Browser (JCEF)\"</i><br>" +
                "<b style='color:#89b4fa;'>3.</b> Install the plugin<br>" +
                "<b style='color:#89b4fa;'>4.</b> Restart IDE<br>" +
                "</div>" +
                "</div></html>"

    private fun crashInstructionsHtml(t: Throwable): String {
        val detail = (t.message ?: t.javaClass.simpleName)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><div style='text-align:center;width:260px;color:#cdd6f4;font-family:sans-serif;font-size:12px;line-height:1.6;'>" +
                "Mindmap crashed while loading this panel.<br><br>" +
                "<span style='color:#6c7086;font-size:11px;'>$detail</span><br><br>" +
                "Please report this on GitHub with your IDE version and the log above." +
                "</div></html>"
    }
}
