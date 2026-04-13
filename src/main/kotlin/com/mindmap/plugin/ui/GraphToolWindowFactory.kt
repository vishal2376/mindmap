package com.mindmap.plugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import java.awt.*
import javax.swing.*

class GraphToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        // Catppuccin Mocha palette
        private val BG = Color(30, 30, 46)
        private val SURFACE1 = Color(49, 50, 68)
        private val SURFACE2 = Color(88, 91, 112)
        private val RED = Color(243, 139, 168)
        private val BLUE = Color(137, 180, 250)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setToHideOnEmptyContent(true)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val errorPanel = createJcefErrorPanel()
            val content = ContentFactory.getInstance().createContent(errorPanel, "Call Graph", false)
            toolWindow.contentManager.addContent(content)
            return
        }
        val panel = MindMapPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Call Graph", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    private fun createJcefErrorPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.background = BG

        val inner = object : JPanel() {
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

        val title = JLabel("JCEF Not Available").apply {
            foreground = RED
            font = Font("SansSerif", Font.BOLD, 16)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val msg = JLabel(
            "<html><div style='text-align:center;width:260px;color:#cdd6f4;font-family:sans-serif;font-size:12px;line-height:1.6;'>" +
            "Mindmap requires <b style='color:#bac2de;'>Chromium (JCEF)</b>.<br><br>" +
            "<div style='text-align:left;display:inline-block;'>" +
            "<b style='color:#89b4fa;'>1. Go to <b>Help → Find Action</b><br>" +
            "<b style='color:#89b4fa;'>2.</b> Search: <i>\"Choose Boot Java Runtime\"</i><br>" +
            "<b style='color:#89b4fa;'>3.</b> Select latest runtime with <b>JCEF</b><br>" +
            "<b style='color:#89b4fa;'>4.</b> Restart IDE<br>" +
            "</div><br><br>" +
            "<span style='color:#6c7086;font-size:11px;'>(IntelliJ IDEA includes JCEF by default)</span>" +
            "</div></html>"
        ).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val link = com.intellij.ui.components.BrowserLink(
            "Not working? Checkout help",
            "https://vishal2376.github.io/mindmap#troubleshoot"
        ).apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = BLUE
        }

        inner.add(title)
        inner.add(Box.createVerticalStrut(12))
        inner.add(msg)
        inner.add(Box.createVerticalStrut(16))
        inner.add(link)
        panel.add(inner)
        return panel
    }
}
