package io.github.noodles_studio.revisiongraph.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class RankingEdge(
    val child: String,
    val parent: String,
    val minLength: Int = 1,
    val cost: Int = 1,
)

/**
 * Computes a cost-minimal layering for an acyclic graph.
 *
 * The objective is the same one used by an optimal Sugiyama ranking: minimize the
 * weighted sum of edge lengths while keeping every parent at least [RankingEdge.minLength]
 * layers below its child. The implementation is self-contained and uses minimum-weight
 * closed sets to improve an initial longest-path ranking.
 */
internal class OptimalRanker {
    fun rank(
        topologicalNodes: List<String>,
        edges: List<RankingEdge>,
        cancelled: () -> Boolean = { false },
    ): Map<String, Int> {
        if (topologicalNodes.isEmpty()) return emptyMap()
        val nodes = topologicalNodes.toSet()
        require(edges.all { it.child in nodes && it.parent in nodes }) { "Ranking edge references an unknown node" }
        require(edges.all { it.minLength > 0 && it.cost > 0 }) { "Ranking edge lengths and costs must be positive" }

        val outgoing = edges.groupBy { it.child }
        val ranks = topologicalNodes.associateWith { 0 }.toMutableMap()
        topologicalNodes.forEach { child ->
            checkCancelled(cancelled)
            outgoing[child].orEmpty().forEach { edge ->
                ranks[edge.parent] = max(ranks.getValue(edge.parent), ranks.getValue(child) + edge.minLength)
            }
        }

        val balance = topologicalNodes.associateWith { 0 }.toMutableMap()
        edges.forEach { edge ->
            balance[edge.child] = balance.getValue(edge.child) - edge.cost
            balance[edge.parent] = balance.getValue(edge.parent) + edge.cost
        }

        while (true) {
            checkCancelled(cancelled)
            val tightEdges = edges.filter { edge ->
                ranks.getValue(edge.parent) - ranks.getValue(edge.child) == edge.minLength
            }
            val selected = minimumWeightClosedSet(topologicalNodes, tightEdges, balance, cancelled)
            val improvementPerLayer = selected.sumOf(balance::getValue)
            if (improvementPerLayer >= 0) break

            val shift = edges.asSequence()
                .filter { it.child in selected && it.parent !in selected }
                .minOfOrNull { edge ->
                    ranks.getValue(edge.parent) - ranks.getValue(edge.child) - edge.minLength
                }
            check(shift != null && shift > 0) { "Improving rank set has no feasible boundary" }
            selected.forEach { node -> ranks[node] = ranks.getValue(node) + shift }
        }

        val firstRank = ranks.values.min()
        return topologicalNodes.associateWith { ranks.getValue(it) - firstRank }
    }

    private fun minimumWeightClosedSet(
        nodes: List<String>,
        tightEdges: List<RankingEdge>,
        weights: Map<String, Int>,
        cancelled: () -> Boolean,
    ): Set<String> {
        val index = nodes.withIndex().associate { it.value to it.index }
        val source = nodes.size
        val sink = source + 1
        val flow = Dinic(nodes.size + 2, cancelled)
        val infinite = weights.values.sumOf(::abs) + 1

        nodes.forEach { node ->
            val weight = weights.getValue(node)
            when {
                weight < 0 -> flow.addEdge(source, index.getValue(node), -weight)
                weight > 0 -> flow.addEdge(index.getValue(node), sink, weight)
            }
        }
        tightEdges.forEach { edge ->
            flow.addEdge(index.getValue(edge.child), index.getValue(edge.parent), infinite)
        }
        flow.maxFlow(source, sink)
        val reachable = flow.reachableFrom(source)
        return nodes.filterTo(linkedSetOf()) { reachable[index.getValue(it)] }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedException("Layout cancelled")
    }

    private class Dinic(
        nodeCount: Int,
        private val cancelled: () -> Boolean,
    ) {
        private class Edge(val to: Int, val reverse: Int, var capacity: Int)

        private val graph = List(nodeCount) { mutableListOf<Edge>() }
        private val level = IntArray(nodeCount)
        private val next = IntArray(nodeCount)

        fun addEdge(from: Int, to: Int, capacity: Int) {
            val forward = Edge(to, graph[to].size, capacity)
            val reverse = Edge(from, graph[from].size, 0)
            graph[from] += forward
            graph[to] += reverse
        }

        fun maxFlow(source: Int, sink: Int) {
            while (buildLevels(source, sink)) {
                next.fill(0)
                while (send(source, sink, Int.MAX_VALUE) > 0) checkCancelled()
            }
        }

        fun reachableFrom(source: Int): BooleanArray {
            val reached = BooleanArray(graph.size)
            val queue = ArrayDeque<Int>()
            reached[source] = true
            queue += source
            while (queue.isNotEmpty()) {
                checkCancelled()
                val node = queue.removeFirst()
                graph[node].forEach { edge ->
                    if (edge.capacity > 0 && !reached[edge.to]) {
                        reached[edge.to] = true
                        queue += edge.to
                    }
                }
            }
            return reached
        }

        private fun buildLevels(source: Int, sink: Int): Boolean {
            level.fill(-1)
            val queue = ArrayDeque<Int>()
            level[source] = 0
            queue += source
            while (queue.isNotEmpty()) {
                checkCancelled()
                val node = queue.removeFirst()
                graph[node].forEach { edge ->
                    if (edge.capacity > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[node] + 1
                        queue += edge.to
                    }
                }
            }
            return level[sink] >= 0
        }

        private fun send(node: Int, sink: Int, available: Int): Int {
            if (node == sink) return available
            while (next[node] < graph[node].size) {
                checkCancelled()
                val edge = graph[node][next[node]]
                if (edge.capacity > 0 && level[edge.to] == level[node] + 1) {
                    val sent = send(edge.to, sink, min(available, edge.capacity))
                    if (sent > 0) {
                        edge.capacity -= sent
                        graph[edge.to][edge.reverse].capacity += sent
                        return sent
                    }
                }
                next[node]++
            }
            return 0
        }

        private fun checkCancelled() {
            if (cancelled()) throw InterruptedException("Layout cancelled")
        }
    }
}
