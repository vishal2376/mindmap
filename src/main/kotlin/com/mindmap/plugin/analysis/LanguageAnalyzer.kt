package com.mindmap.plugin.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

interface LanguageAnalyzer {
    fun canAnalyze(psiFile: PsiFile): Boolean
    fun findFunctionAtOffset(psiFile: PsiFile, offset: Int): PsiElement?
    fun buildGraph(element: PsiElement, indicator: ProgressIndicator? = null): GraphData
    fun languageName(): String
}
