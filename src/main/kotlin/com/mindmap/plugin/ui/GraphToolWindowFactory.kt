package com.mindmap.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GraphToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MindMapPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Call Graph", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
