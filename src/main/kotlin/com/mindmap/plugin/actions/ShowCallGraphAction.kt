package com.mindmap.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.util.PsiTreeUtil
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.ui.MindMapPanel
import org.jetbrains.kotlin.psi.KtNamedFunction

class ShowCallGraphAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        val function = runReadAction {
            PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        } ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Mindmap") ?: return
        toolWindow.show()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating Mindmap for ${function.name}", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing call graph..."

                val analyzer = GraphAnalyzer(project)
                val graphData = analyzer.buildGraph(function, indicator)

                val content = toolWindow.contentManager.getContent(0) ?: return
                val panel = content.component as? MindMapPanel ?: return
                panel.updateGraph(graphData)
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        var visible = false
        if (psiFile != null && editor != null) {
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset)
            if (element != null) {
                visible = runReadAction {
                    PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) != null
                }
            }
        }

        e.presentation.isEnabledAndVisible = visible
    }
}
