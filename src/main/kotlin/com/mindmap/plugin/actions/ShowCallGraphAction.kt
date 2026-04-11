package com.mindmap.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager
import com.mindmap.plugin.analysis.LanguageAnalyzerRegistry
import com.mindmap.plugin.ui.MindMapPanel

class ShowCallGraphAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(ShowCallGraphAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        try {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

            val analyzer = runReadAction {
                LanguageAnalyzerRegistry.findAnalyzer(project, psiFile)
            } ?: return

            val offset = editor.caretModel.offset
            val function = runReadAction {
                analyzer.findFunctionAtOffset(psiFile, offset)
            } ?: return

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Mindmap") ?: return
            toolWindow.show()

            val langName = analyzer.languageName()

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, "Generating Mindmap...", true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Analyzing $langName call graph..."

                        val graphData = analyzer.buildGraph(function, indicator)

                        val content = toolWindow.contentManager.getContent(0) ?: return
                        val panel = content.component as? MindMapPanel ?: return
                        panel.updateGraph(graphData)
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.error("Failed to generate mindmap", ex)
                    }
                }
            })
        } catch (ex: Exception) {
            LOG.error("Mindmap action failed", ex)
        }
    }

    override fun update(e: AnActionEvent) {
        try {
            val project = e.project
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            val editor = e.getData(CommonDataKeys.EDITOR)

            var visible = false
            if (project != null && psiFile != null && editor != null) {
                val offset = editor.caretModel.offset
                visible = runReadAction {
                    val analyzer = LanguageAnalyzerRegistry.findAnalyzer(project, psiFile) ?: return@runReadAction false
                    analyzer.findFunctionAtOffset(psiFile, offset) != null
                }
            }

            e.presentation.isEnabledAndVisible = visible
        } catch (ex: Exception) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
