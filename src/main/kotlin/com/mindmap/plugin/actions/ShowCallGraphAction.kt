package com.mindmap.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.mindmap.plugin.DebugLog
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.SwingUtilities
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.ui.MindMapPanel
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Alt+G action that triggers call graph generation for the Kotlin function at the cursor.
 *
 * Finds the innermost [KtNamedFunction] at the caret position, opens the Mindmap tool window,
 * then runs [GraphAnalyzer] on a background thread to avoid blocking the EDT.
 */
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

            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset) ?: return

            val function = ReadAction.compute<KtNamedFunction?, RuntimeException> {
                PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
            } ?: return

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Mindmap") ?: return
            toolWindow.show()

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, "Generating Mindmap for ${function.name ?: "function"}", true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Analyzing Kotlin call graph..."

                        val graphData = GraphAnalyzer(project).buildGraph(function, indicator)

                        // UI access and history mutations must happen on EDT.
                        SwingUtilities.invokeLater {
                            val content = toolWindow.contentManager.getContent(0) ?: return@invokeLater
                            val panel = content.component as? MindMapPanel ?: return@invokeLater
                            panel.updateGraph(graphData)
                        }
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.error("Failed to generate mindmap", ex); DebugLog.error("Failed to generate mindmap", ex)
                    }
                }
            })
        } catch (ex: Exception) {
            LOG.error("Mindmap action failed", ex); DebugLog.error("Mindmap action failed", ex)
        }
    }

    // BGT thread required: PSI reads must not block the EDT.
    // Action is enabled only when the caret is inside a named Kotlin function.
    override fun update(e: AnActionEvent) {
        try {
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            val editor = e.getData(CommonDataKeys.EDITOR)

            var visible = false
            if (psiFile != null && editor != null) {
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset)
                if (element != null) {
                    visible = ReadAction.compute<Boolean, RuntimeException> {
                        PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) != null
                    }
                }
            }

            e.presentation.isEnabledAndVisible = visible
        } catch (ex: Exception) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
