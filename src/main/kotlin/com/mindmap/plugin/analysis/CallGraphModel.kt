package com.mindmap.plugin.analysis

import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Position of a node relative to the root function in the call graph.
 * ROOT is the function the user triggered analysis on.
 * OUTBOUND nodes are functions called by the root (direct or transitive).
 * INBOUND nodes are functions that call the root (callers).
 */
enum class NodeType {
    ROOT,
    OUTBOUND,
    INBOUND
}

/**
 * A single function node in the call graph.
 *
 * @property id Stable unique key derived from the fully qualified name and parameter types.
 * @property name Simple function name shown in the UI.
 * @property signature Full signature including parameter names, types, and return type.
 * @property fileName Source file name, shown as a secondary label in the tree view.
 * @property type Position relative to root: ROOT, OUTBOUND, or INBOUND.
 * @property depth Hops from the root. Root is 0; direct callers/callees are 1.
 * @property isLibrary True if the function lives outside project source (e.g. stdlib, third-party).
 *   Library nodes are shown but not recursed into to keep the graph bounded.
 * @property loc Approximate line count of the function body, shown as a size hint in the UI.
 * @property children IDs of outbound nodes called by this function.
 * @property parents IDs of inbound nodes that call this function.
 * @property psiElement Live PSI handle for navigation and expand. Marked @Transient so Gson
 *   skips it when serializing graph data to JSON for the browser.
 */
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

/**
 * Merges [other] into this graph, deduplicating nodes and edges by ID.
 * Used when a trace operation adds new callers/callees to an existing graph
 * without discarding the nodes already on screen.
 */
fun GraphData.merge(other: GraphData): GraphData {
    val seenIds   = nodes.mapTo(HashSet()) { it.id }
    val seenEdges = edges.mapTo(HashSet()) { "${it.from}\u0000${it.to}" }
    val newNodes  = nodes.toMutableList()
    val newEdges  = edges.toMutableList()
    for (node in other.nodes) { if (seenIds.add(node.id)) newNodes.add(node) }
    for (edge in other.edges) { if (seenEdges.add("${edge.from}\u0000${edge.to}")) newEdges.add(edge) }
    return GraphData(newNodes, newEdges)
}
