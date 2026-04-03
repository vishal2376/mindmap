package com.mindmap.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import java.awt.*
import javax.swing.*

class GraphToolWindowFactory : ToolWindowFactory {

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
        panel.background = Color(30, 30, 46)

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(30, 30, 46)
            border = BorderFactory.createEmptyBorder(20, 40, 20, 40)
        }

        val title = JLabel("JCEF Not Available").apply {
            foreground = Color(243, 139, 168)
            font = Font("SansSerif", Font.BOLD, 15)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val msg = JLabel(
            "<html><div style='text-align:center;width:320px;color:#cdd6f4;'>" +
            "Mindmap requires JCEF (Chromium Embedded Framework) to render the interactive graph.<br><br>" +
            "<b style='color:#89b4fa;'>To fix this in Android Studio:</b><br>" +
            "1. Go to <b>Help → Find Action</b><br>" +
            "2. Search <b>\"Choose Boot Java Runtime\"</b><br>" +
            "3. Select a runtime with <b>JCEF</b><br>" +
            "4. Restart the IDE<br><br>" +
            "<span style='color:#a6adc8;'>IntelliJ IDEA includes JCEF by default.</span>" +
            "</div></html>"
        ).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        inner.add(title)
        inner.add(Box.createVerticalStrut(12))
        inner.add(msg)
        panel.add(inner)
        return panel
    }
}
