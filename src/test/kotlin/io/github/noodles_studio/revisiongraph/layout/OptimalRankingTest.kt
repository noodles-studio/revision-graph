package io.github.noodles_studio.revisiongraph.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptimalRankingTest {
    @Test fun `short side branch is pulled next to its deep parent`() {
        val nodes = listOf("main-tip", "main-1", "side-tip", "main-2", "root")
        val edges = listOf(
            RankingEdge("main-tip", "main-1"),
            RankingEdge("main-1", "main-2"),
            RankingEdge("main-2", "root"),
            RankingEdge("side-tip", "root"),
        )

        val ranks = OptimalRanker().rank(nodes, edges)

        assertEquals(1, ranks.getValue("root") - ranks.getValue("side-tip"))
        assertEquals(0, ranks.getValue("main-tip"))
        assertEquals(3, ranks.getValue("root"))
    }

    @Test fun `ranking is cost minimal for every four node dag`() {
        val nodes = listOf("a", "b", "c", "d")
        val possibleEdges = buildList {
            nodes.indices.forEach { child ->
                for (parent in child + 1 until nodes.size) add(RankingEdge(nodes[child], nodes[parent]))
            }
        }

        repeat(1 shl possibleEdges.size) { mask ->
            val edges = possibleEdges.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val ranks = OptimalRanker().rank(nodes, edges)
            assertFeasible(ranks, edges)
            assertEquals(bruteForceMinimum(nodes, edges), objective(ranks, edges), "edge mask $mask")
            assertEquals(ranks, OptimalRanker().rank(nodes, edges), "edge mask $mask must be deterministic")
        }
    }

    @Test fun `custom minimum lengths and costs are respected`() {
        val nodes = listOf("a", "b", "c", "d")
        val edges = listOf(
            RankingEdge("a", "b", minLength = 2, cost = 4),
            RankingEdge("b", "d", cost = 4),
            RankingEdge("c", "d"),
        )

        val ranks = OptimalRanker().rank(nodes, edges)

        assertFeasible(ranks, edges)
        assertEquals(bruteForceMinimum(nodes, edges, maxRank = 5), objective(ranks, edges))
    }

    @Test fun `ranking observes cancellation`() {
        assertFailsWith<InterruptedException> {
            OptimalRanker().rank(listOf("a", "b"), listOf(RankingEdge("a", "b"))) { true }
        }
    }

    @Test fun `many short branch tips stay next to a deep main history`() {
        val sideTips = List(100) { "side-$it" }
        val main = List(80) { "main-$it" }
        val nodes = sideTips + main
        val edges = buildList {
            main.zipWithNext().forEach { (child, parent) -> add(RankingEdge(child, parent)) }
            sideTips.forEachIndexed { index, tip -> add(RankingEdge(tip, main[20 + index % 60])) }
        }

        val ranks = OptimalRanker().rank(nodes, edges)

        assertFeasible(ranks, edges)
        sideTips.forEachIndexed { index, tip ->
            assertEquals(1, ranks.getValue(main[20 + index % 60]) - ranks.getValue(tip))
        }
    }

    private fun assertFeasible(ranks: Map<String, Int>, edges: List<RankingEdge>) {
        assertEquals(0, ranks.values.min())
        edges.forEach { edge ->
            assertTrue(ranks.getValue(edge.parent) - ranks.getValue(edge.child) >= edge.minLength)
        }
    }

    private fun objective(ranks: Map<String, Int>, edges: List<RankingEdge>): Int = edges.sumOf { edge ->
        edge.cost * (ranks.getValue(edge.parent) - ranks.getValue(edge.child))
    }

    private fun bruteForceMinimum(nodes: List<String>, edges: List<RankingEdge>, maxRank: Int = nodes.size): Int {
        var best = Int.MAX_VALUE
        val ranks = IntArray(nodes.size)
        fun visit(index: Int) {
            if (index < nodes.size) {
                for (rank in 0..maxRank) {
                    ranks[index] = rank
                    visit(index + 1)
                }
                return
            }
            if (ranks.min() != 0) return
            val byNode = nodes.indices.associate { nodes[it] to ranks[it] }
            if (edges.any { byNode.getValue(it.parent) - byNode.getValue(it.child) < it.minLength }) return
            best = minOf(best, objective(byNode, edges))
        }
        visit(0)
        return best
    }
}
