package com.mindmap.plugin.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

object LanguageAnalyzerRegistry {

    private val LOG = Logger.getInstance(LanguageAnalyzerRegistry::class.java)

    fun findAnalyzer(project: Project, psiFile: PsiFile, outboundDepth: Int = 3, inboundDepth: Int = 2): LanguageAnalyzer? {
        // Kotlin — always available (required dependency)
        try {
            val kotlinAnalyzer = KotlinAnalyzer(project, outboundDepth, inboundDepth)
            if (kotlinAnalyzer.canAnalyze(psiFile)) return kotlinAnalyzer
        } catch (e: Exception) {
            LOG.warn("Kotlin analyzer unavailable", e)
        }

        // Java — available if com.intellij.java plugin is loaded
        try {
            Class.forName("com.intellij.psi.PsiMethod")
            val javaAnalyzer = JavaAnalyzer(project, outboundDepth, inboundDepth)
            if (javaAnalyzer.canAnalyze(psiFile)) return javaAnalyzer
        } catch (_: ClassNotFoundException) {
            // Java plugin not available
        } catch (e: Exception) {
            LOG.warn("Java analyzer unavailable", e)
        }

        // Python — available if Python plugin is loaded (future: requires PyCharm or IntelliJ + Python plugin)
        // PythonAnalyzer will be enabled once the build supports PythonCore dependency
        // try {
        //     Class.forName("com.jetbrains.python.psi.PyFunction")
        //     val pythonAnalyzer = PythonAnalyzer(project, outboundDepth, inboundDepth)
        //     if (pythonAnalyzer.canAnalyze(psiFile)) return pythonAnalyzer
        // } catch (_: ClassNotFoundException) {
        //     // Python plugin not available
        // } catch (e: Exception) {
        //     LOG.warn("Python analyzer unavailable", e)
        // }

        return null
    }
}
