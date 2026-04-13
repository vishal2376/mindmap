package com.mindmap.plugin.analysis

import org.jetbrains.kotlin.psi.KtNamedFunction

enum class NodeType {
    ROOT,
    OUTBOUND,
    INBOUND
}

data class CallGraphNode(
    val id: String,
    val name: String,
    val signature: String,
    val fileName: String = "",
    val type: NodeType,
    val depth: Int = 0,
    val isLibrary: Boolean = false,
    val loc: Int = 0,
    val children: MutableList<String> = mutableListOf(),
    val parents: MutableList<String> = mutableListOf()
) {
    @Transient
    var psiElement: KtNamedFunction? = null
}

data class CallGraphEdge(
    val from: String,
    val to: String
)

data class GraphData(
    val nodes: List<CallGraphNode>,
    val edges: List<CallGraphEdge>
)

/** Merges [other] into this graph, deduplicating nodes and edges by ID. */
fun GraphData.merge(other: GraphData): GraphData {
    val seenIds   = nodes.mapTo(HashSet()) { it.id }
    val seenEdges = edges.mapTo(HashSet()) { "${it.from}\u0000${it.to}" }
    val newNodes  = nodes.toMutableList()
    val newEdges  = edges.toMutableList()
    for (node in other.nodes) { if (seenIds.add(node.id)) newNodes.add(node) }
    for (edge in other.edges) { if (seenEdges.add("${edge.from}\u0000${edge.to}")) newEdges.add(edge) }
    return GraphData(newNodes, newEdges)
}
