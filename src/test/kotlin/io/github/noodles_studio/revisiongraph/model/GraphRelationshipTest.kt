package io.github.noodles_studio.revisiongraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphRelationshipTest {
    @Test
    fun `diverged revisions expose one path per selection`() {
        val root = commit('a')
        val left1 = commit('b', root)
        val alternate = commit('c', root)
        val left2 = commit('d', left1, alternate)
        val right = commit('e', root)
        val snapshot = snapshot(left2, left1, alternate, right, root)

        val relationship = snapshot.relationship(left2.hash, right.hash)!!

        assertEquals(setOf(EdgeKey(left2.hash, left1.hash), EdgeKey(left1.hash, root.hash)), relationship.basePath)
        assertEquals(setOf(EdgeKey(right.hash, root.hash)), relationship.targetPath)
        assertTrue(EdgeKey(left2.hash, alternate.hash) !in relationship.basePath)
    }

    @Test
    fun `ancestor relationship is classified in selection direction`() {
        val root = commit('a')
        val tip = commit('b', root)

        val relationship = snapshot(tip, root).relationship(root.hash, tip.hash)!!

        assertEquals(emptySet(), relationship.basePath)
        assertEquals(setOf(EdgeKey(tip.hash, root.hash)), relationship.targetPath)
    }

    @Test
    fun `newer common ancestor wins even when an older ancestor has a shorter shortcut`() {
        val root = commit('a')
        val common = commit('b', root)
        val step1 = commit('c', common)
        val step2 = commit('d', step1)
        val step3 = commit('e', step2)
        val tip = commit('f', step3, root)

        val relationship = snapshot(tip, step3, step2, step1, common, root).relationship(tip.hash, common.hash)!!

        assertEquals(
            setOf(
                EdgeKey(tip.hash, step3.hash),
                EdgeKey(step3.hash, step2.hash),
                EdgeKey(step2.hash, step1.hash),
                EdgeKey(step1.hash, common.hash),
            ),
            relationship.basePath,
        )
        assertEquals(emptySet(), relationship.targetPath)
        assertTrue(EdgeKey(tip.hash, root.hash) !in relationship.basePath)
    }

    private fun commit(value: Char, vararg parents: CommitNode) = CommitNode(
        hash = value.toString().repeat(40),
        parents = parents.map(CommitNode::hash),
        epochSeconds = 0,
        subject = value.toString(),
    )

    private fun snapshot(vararg commits: CommitNode) = GraphSnapshot(
        commits.toList(),
        emptyMap(),
        HeadState(null, null, false),
    )
}
