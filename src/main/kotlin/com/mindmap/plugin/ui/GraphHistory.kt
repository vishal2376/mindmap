package com.mindmap.plugin.ui

import com.mindmap.plugin.analysis.GraphData
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Manages navigation history for the mindmap graph.
 * All methods must be called on EDT.
 */
class GraphHistory(private val maxSize: Int = 50) {

    private val entries = mutableListOf<GraphData>()
    private var index = -1

    val current: GraphData? get() = if (index in entries.indices) entries[index] else null
    val canBack: Boolean get() = index > 0
    val canForward: Boolean get() = index < entries.size - 1
    val position: Int get() = index + 1
    val total: Int get() = entries.size

    /** Clears history and sets the given data as the only entry. */
    fun reset(data: GraphData) {
        entries.clear()
        entries.add(data)
        index = 0
    }

    /** Pushes new data, trimming any forward history. */
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

    /** Navigates back, returns the graph data or null if at start. */
    fun back(): GraphData? {
        if (!canBack) return null
        index--
        return entries[index]
    }

    /** Navigates forward, returns the graph data or null if at end. */
    fun forward(): GraphData? {
        if (!canForward) return null
        index++
        return entries[index]
    }

    /** Replaces the current entry (used by trace merge). */
    fun updateCurrent(data: GraphData) {
        if (index in entries.indices) {
            entries[index] = data
        }
    }

    /** Clears all entries and resets index. */
    fun clear() {
        entries.clear()
        index = -1
    }

    /** Searches current and historical graph data for a valid PSI element. */
    fun findPsiElement(nodeId: String): KtNamedFunction? {
        return try {
            current?.nodes?.find { it.id == nodeId }?.psiElement?.let {
                if (it.isValid) return it
            }
            for (graphData in entries.asReversed()) {
                graphData.nodes.find { it.id == nodeId }?.psiElement?.let {
                    if (it.isValid) return it
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
