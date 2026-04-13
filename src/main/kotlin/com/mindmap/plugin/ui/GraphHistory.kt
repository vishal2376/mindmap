package com.mindmap.plugin.ui

import com.intellij.openapi.application.ReadAction
import com.mindmap.plugin.analysis.GraphData
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Browser-like back/forward navigation stack for call graph views.
 *
 * [reset] starts a fresh history (used when Alt+G opens a new root function).
 * [push] adds a new entry and discards any forward history (used by expand/re-center).
 * [updateCurrent] replaces the current entry in-place (used when a trace merges new nodes
 * into the visible graph without creating a new history step).
 *
 * All methods must be called on the EDT.
 */
class GraphHistory(private val maxSize: Int = 50) {

    private val entries = mutableListOf<GraphData>()
    private var index = -1

    val current: GraphData? get() = if (index in entries.indices) entries[index] else null
    val canBack: Boolean get() = index > 0
    val canForward: Boolean get() = index < entries.size - 1
    val position: Int get() = index + 1
    val total: Int get() = entries.size

    fun reset(data: GraphData) {
        entries.clear()
        entries.add(data)
        index = 0
    }

    /** Discards forward history, then appends [data]. Evicts oldest entry if over [maxSize]. */
    fun push(data: GraphData) {
        if (index < entries.size - 1) {
            while (entries.size > index + 1) {
                entries.removeAt(entries.size - 1)
            }
        }
        if (entries.size >= maxSize) {
            entries.removeAt(0)
            index = (index - 1).coerceAtLeast(0)
        }
        entries.add(data)
        index = entries.size - 1
    }

    fun back(): GraphData? {
        if (!canBack) return null
        index--
        return entries[index]
    }

    fun forward(): GraphData? {
        if (!canForward) return null
        index++
        return entries[index]
    }

    /** Replaces the current entry in-place (used when trace merges new nodes). */
    fun updateCurrent(data: GraphData) {
        if (index in entries.indices) {
            entries[index] = data
        }
    }

    fun clear() {
        entries.clear()
        index = -1
    }

    /**
     * Finds a live PSI element for [nodeId] by searching current and past history entries.
     * History is searched because PSI elements can become invalid after file edits,
     * and an older snapshot may still hold a valid reference for the same node.
     */
    fun findPsiElement(nodeId: String): KtNamedFunction? {
        return try {
            // .isValid is a PSI read — must be inside ReadAction regardless of calling thread.
            ReadAction.compute<KtNamedFunction?, RuntimeException> {
                current?.nodes?.find { it.id == nodeId }?.psiElement?.takeIf { it.isValid }
                    ?: entries.asReversed().firstNotNullOfOrNull { graphData ->
                        graphData.nodes.find { it.id == nodeId }?.psiElement?.takeIf { it.isValid }
                    }
            }
        } catch (_: Exception) {
            null
        }
    }
}
