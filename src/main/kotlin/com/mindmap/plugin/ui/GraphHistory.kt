package com.mindmap.plugin.ui

import com.mindmap.plugin.analysis.GraphData
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Navigation history for mindmap graphs. All methods must be called on EDT. */
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

    /** Trims forward history before pushing. */
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

    /** Searches current graph then history for a valid PSI element matching this node ID. */
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
