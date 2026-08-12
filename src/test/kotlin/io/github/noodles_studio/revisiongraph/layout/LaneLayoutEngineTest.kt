package io.github.noodles_studio.revisiongraph.layout

import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LayeredDagLayoutEngineTest {
    private fun node(id: Char, vararg parents: Char) = CommitNode(id.toString().repeat(40), parents.map { it.toString().repeat(40) }, 0, id.toString())
    private fun graph(vararg commits: CommitNode) = GraphSnapshot(commits.toList(), emptyMap(), HeadState(null, null, false))

    @Test fun `child is above every loaded parent even when input is shuffled`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a', 'b', 'c'), node('c', 'd'), node('d'), node('b', 'd')))
        layout.edges.forEach { assertTrue(layout.byHash.getValue(it.child).row < layout.byHash.getValue(it.parent).row) }
    }

    @Test fun `first parent stays in lane through merge`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a', 'b', 'c', 'd'), node('b', 'e'), node('c', 'e'), node('d', 'e'), node('e')))
        assertEquals(layout.byHash.getValue("a".repeat(40)).lane, layout.byHash.getValue("b".repeat(40)).lane)
    }

    @Test fun `same input produces identical routes`() {
        val input = graph(node('a', 'b', 'c'), node('b', 'd'), node('c', 'd'), node('d'))
        val first = LayeredDagLayoutEngine().layout(input); val second = LayeredDagLayoutEngine().layout(input)
        assertEquals(first.nodes, second.nodes); assertEquals(first.edges, second.edges); assertEquals(first.bounds, second.bounds)
    }

    @Test fun `missing parents do not create phantom nodes`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a', 'z')))
        assertEquals(1, layout.nodes.size); assertTrue(layout.edges.isEmpty())
    }

    @Test fun `cancelled layout never completes`() {
        assertFailsWith<InterruptedException> { LayeredDagLayoutEngine().layout(graph(node('a'))) { true } }
    }

    @Test fun `multiple refs become rows inside one taller original style node`() {
        val commit = node('a')
        val refs = listOf(
            RevisionRef("refs/heads/main", commit.hash, RefKind.LOCAL_BRANCH),
            RevisionRef("refs/remotes/origin/main", commit.hash, RefKind.REMOTE_BRANCH),
            RevisionRef("refs/tags/v1", commit.hash, RefKind.TAG),
        )
        val layout = LayeredDagLayoutEngine().layout(GraphSnapshot(listOf(commit), mapOf(commit.hash to refs), HeadState(commit.hash, "main", false)))
        assertEquals(EstimatedGraphTextMetrics.rowHeight() * 3, layout.nodes.single().bounds.height)
    }

    @Test fun `complete ref names are measured without the old width cap`() {
        val commit = node('a')
        val longName = "feature/" + "wide-branch-name-".repeat(8)
        val ref = RevisionRef("refs/heads/$longName", commit.hash, RefKind.LOCAL_BRANCH)
        val layout = LayeredDagLayoutEngine().layout(
            GraphSnapshot(listOf(commit), mapOf(commit.hash to listOf(ref)), HeadState(null, null, false)),
        )

        assertTrue(layout.nodes.single().bounds.width > 252.0)
        assertTrue(layout.nodes.single().bounds.width >= EstimatedGraphTextMetrics.textWidth(longName, false) + 40.0)
    }

    @Test fun `wide nodes on the same layer keep the configured safety gap`() {
        val a = node('a', 'c')
        val b = node('b', 'd')
        val c = node('c')
        val d = node('d')
        val refs = mapOf(
            a.hash to listOf(RevisionRef("refs/remotes/origin/a-very-long-feature-branch-name", a.hash, RefKind.REMOTE_BRANCH)),
            b.hash to listOf(RevisionRef("refs/remotes/origin/another-very-long-feature-name", b.hash, RefKind.REMOTE_BRANCH)),
        )
        val layout = LayeredDagLayoutEngine().layout(GraphSnapshot(listOf(a, b, c, d), refs, HeadState(null, null, false)))

        layout.nodes.groupBy { it.row }.values.forEach { rank ->
            rank.sortedBy { it.bounds.minX }.zipWithNext().forEach { (left, right) ->
                assertTrue(right.bounds.minX - left.bounds.maxX >= 25.0)
            }
        }
    }

    @Test fun `disconnected root commits on one layer receive distinct lanes`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a'), node('b'), node('c')))
        val nodes = layout.nodes.sortedBy { it.bounds.minX }

        assertEquals(3, nodes.map { it.lane }.distinct().size)
        nodes.zipWithNext().forEach { (left, right) ->
            assertTrue(right.bounds.minX - left.bounds.maxX >= 25.0)
        }
    }

    @Test fun `layers account for the tallest compound ref node`() {
        val a = node('a', 'c')
        val b = node('b', 'd')
        val c = node('c')
        val d = node('d')
        val refs = mapOf(
            a.hash to listOf(
                RevisionRef("refs/heads/main", a.hash, RefKind.LOCAL_BRANCH),
                RevisionRef("refs/remotes/origin/main", a.hash, RefKind.REMOTE_BRANCH),
                RevisionRef("refs/tags/v1", a.hash, RefKind.TAG),
            ),
        )
        val layout = LayeredDagLayoutEngine().layout(GraphSnapshot(listOf(a, b, c, d), refs, HeadState(a.hash, "main", false)))
        listOf(a.hash to c.hash, b.hash to d.hash).forEach { (child, parent) ->
            val upperBottom = layout.byHash.getValue(child).bounds.maxY
            val lowerTop = layout.byHash.getValue(parent).bounds.minY
            assertTrue(lowerTop - upperBottom >= 30.0)
        }
    }

    @Test fun `adjacent layers use one original-style straight segment`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a', 'b', 'c'), node('b', 'd'), node('c', 'd'), node('d')))
        val crossLane = layout.edges.first { layout.byHash.getValue(it.child).lane != layout.byHash.getValue(it.parent).lane }

        assertEquals(2, crossLane.points.size)
        assertNotEquals(crossLane.points.first().x, crossLane.points.last().x)
        assertTrue(crossLane.points.first().y < crossLane.points.last().y)
    }

    @Test fun `long edges use an inter-lane channel instead of crossing intermediate nodes`() {
        val layout = LayeredDagLayoutEngine().layout(graph(node('a', 'b', 'd'), node('b', 'c'), node('c', 'd'), node('d')))
        val longEdge = layout.edges.first { it.child == "a".repeat(40) && it.parent == "d".repeat(40) }
        val unrelated = layout.nodes.filter { it.hash != longEdge.child && it.hash != longEdge.parent }

        assertTrue(longEdge.points.size >= 3)
        longEdge.points.zipWithNext().forEach { (from, to) ->
            unrelated.forEach { node ->
                assertFalse(node.bounds.intersectsLine(from.x, from.y, to.x, to.y))
            }
        }
    }

    @Test fun `detached head adds a row without overlapping the next layer`() {
        val a = node('a', 'b')
        val b = node('b')
        val tag = RevisionRef("refs/tags/v1", a.hash, RefKind.TAG)
        val layout = LayeredDagLayoutEngine().layout(GraphSnapshot(listOf(a, b), mapOf(a.hash to listOf(tag)), HeadState(a.hash, null, true)))

        assertEquals(EstimatedGraphTextMetrics.rowHeight() * 2, layout.byHash.getValue(a.hash).bounds.height)
        assertTrue(layout.byHash.getValue(b.hash).bounds.minY - layout.byHash.getValue(a.hash).bounds.maxY >= 30.0)
    }

    @Test fun `compound nodes never overlap across a mixed graph`() {
        val a = node('a', 'b', 'c')
        val b = node('b', 'd')
        val c = node('c', 'd')
        val d = node('d')
        val e = node('e', 'f')
        val f = node('f')
        val refs = mapOf(
            a.hash to listOf(
                RevisionRef("refs/heads/main", a.hash, RefKind.LOCAL_BRANCH),
                RevisionRef("refs/remotes/origin/main", a.hash, RefKind.REMOTE_BRANCH),
                RevisionRef("refs/tags/v2.18.0", a.hash, RefKind.TAG),
            ),
            c.hash to listOf(RevisionRef("refs/remotes/origin/a-very-long-feature-branch", c.hash, RefKind.REMOTE_BRANCH)),
            e.hash to listOf(RevisionRef("refs/heads/release", e.hash, RefKind.LOCAL_BRANCH)),
        )
        val layout = LayeredDagLayoutEngine().layout(
            GraphSnapshot(listOf(e, a, c, f, b, d), refs, HeadState(a.hash, "main", false)),
        )

        layout.nodes.forEachIndexed { index, left ->
            layout.nodes.drop(index + 1).forEach { right ->
                assertFalse(left.bounds.intersects(right.bounds), "${left.hash.take(7)} overlaps ${right.hash.take(7)}")
            }
        }
    }
}
