package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.RevisionLogTarget
import io.github.noodles_studio.revisiongraph.model.revisionLogTarget

import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import io.github.noodles_studio.revisiongraph.git.RevisionGraphFilter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisionSelectionTest {
    @Test fun `focused revision stays near the top while small canvases remain safe`() {
        assertEquals(96.0, focusScreenY(800))
        assertEquals(144.0, focusScreenY(1_200))
        assertEquals(75.0, focusScreenY(150))
    }

    @Test fun `focused revision moves down enough to reveal content above it`() {
        assertEquals(184.0, focusScreenY(800, contentAbove = 160.0))
        assertEquals(280.0, focusScreenY(800, contentAbove = 500.0))
        assertEquals(96.0, focusScreenY(800, contentAbove = -20.0))
    }

    @Test fun `zoom percentage accepts presets and manual decimal input`() {
        assertEquals(125.0, parseZoomPercent("125%"))
        assertEquals(12.5, parseZoomPercent(" 12,5 % "))
        assertNull(parseZoomPercent("not a zoom"))
        assertNull(parseZoomPercent("0%"))
    }

    @Test fun `revision locator ranks exact current and local refs before remote and tags`() {
        val hashA = "a".repeat(40)
        val hashB = "b".repeat(40)
        val hashC = "c".repeat(40)
        val refs = mapOf(
            hashA to listOf(RevisionRef("refs/heads/test", hashA, RefKind.LOCAL_BRANCH)),
            hashB to listOf(RevisionRef("refs/remotes/origin/test", hashB, RefKind.REMOTE_BRANCH)),
            hashC to listOf(RevisionRef("refs/tags/test-release", hashC, RefKind.TAG)),
        )
        val snapshot = GraphSnapshot(
            listOf(CommitNode(hashA, emptyList(), 0, "a"), CommitNode(hashB, emptyList(), 0, "b"), CommitNode(hashC, emptyList(), 0, "c")),
            refs,
            HeadState(hashA, "test", false),
        )

        val results = findRevisionRefs(snapshot, "test")

        assertEquals(listOf("test", "origin/test", "test-release"), results.map { it.displayName })
        assertTrue(results.first().current)
    }

    @Test fun `revision locator matches full ref names case insensitively`() {
        val commits = (0..3).map { index -> CommitNode(index.toString().repeat(40), emptyList(), 0, "$index") }
        val refs = commits.associate { commit ->
            commit.hash to listOf(RevisionRef("refs/remotes/Origin/Feature-${commit.subject}", commit.hash, RefKind.REMOTE_BRANCH))
        }
        val snapshot = GraphSnapshot(commits, refs, HeadState(null, null, false))

        val results = findRevisionRefs(snapshot, "REFS/REMOTES/ORIGIN/FEATURE")

        assertEquals(4, results.size)
        assertTrue(results.all { it.kind == RefKind.REMOTE_BRANCH })
        assertTrue(findRevisionRefs(snapshot, "   ").isEmpty())
    }

    @Test fun `revision locator cycles forward and backward like original find next`() {
        assertEquals(0, cyclicLocatorIndex(3, -1, reverse = false))
        assertEquals(1, cyclicLocatorIndex(3, 0, reverse = false))
        assertEquals(0, cyclicLocatorIndex(3, 2, reverse = false))
        assertEquals(2, cyclicLocatorIndex(3, -1, reverse = true))
        assertEquals(2, cyclicLocatorIndex(3, 0, reverse = true))
        assertEquals(1, cyclicLocatorIndex(3, 2, reverse = true))
    }

    @Test fun `filter suggestions include readable and full ref names`() {
        val graph = snapshot(
            HeadState("a", "test", false),
            RevisionRef("refs/heads/test", "a", RefKind.LOCAL_BRANCH),
            RevisionRef("refs/remotes/origin/test", "a", RefKind.REMOTE_BRANCH),
        )

        val suggestions = revisionFilterSuggestions(graph)

        assertTrue("HEAD" in suggestions)
        assertTrue("test" in suggestions)
        assertTrue("origin/test" in suggestions)
        assertTrue("refs/heads/test" in suggestions)
        assertTrue("remotes/origin/test" in suggestions)
    }

    @Test fun `filter focuses explicit tip before head and otherwise prefers head`() {
        val graph = GraphSnapshot(
            commits = listOf(CommitNode("a", emptyList(), 0, "head"), CommitNode("b", listOf("a"), 0, "feature")),
            refsByCommit = mapOf("b" to listOf(RevisionRef("refs/heads/feature", "b", RefKind.LOCAL_BRANCH))),
            head = HeadState("a", "test", false),
        )

        assertEquals("b", preferredFilterFocusHash(graph, RevisionGraphFilter(includedRevisions = listOf("feature"))))
        assertEquals("a", preferredFilterFocusHash(graph, RevisionGraphFilter.NONE))
    }

    @Test fun `plain click selects base and clicking it again clears selection`() {
        val selected = RevisionSelection.EMPTY.click("a", additive = false)

        assertEquals(RevisionSelection("a", activeHash = "a"), selected)
        assertEquals(RevisionSelection.EMPTY, selected.click("a", additive = false))
    }

    @Test fun `plain click on another node replaces base and target`() {
        val pair = RevisionSelection("a", "b", "b")

        assertEquals(RevisionSelection("c", activeHash = "c"), pair.click("c", additive = false))
        assertEquals(RevisionSelection("b", activeHash = "b"), pair.click("b", additive = false))
    }

    @Test fun `additive click creates and removes target`() {
        val base = RevisionSelection.EMPTY.click("a", additive = true)
        val pair = base.click("b", additive = true)

        assertEquals(RevisionSelection("a", activeHash = "a"), base)
        assertEquals(RevisionSelection("a", "b", "b"), pair)
        assertEquals(base, pair.click("b", additive = true))
    }

    @Test fun `removing base promotes target to base`() {
        val pair = RevisionSelection("a", "b", "b")

        assertEquals(RevisionSelection("b", activeHash = "b"), pair.click("a", additive = true))
    }

    @Test fun `third additive node replaces target`() {
        val pair = RevisionSelection("a", "b", "b")

        assertEquals(RevisionSelection("a", "c", "c"), pair.click("c", additive = true))
    }

    @Test fun `plain blank click clears while additive blank click preserves`() {
        val pair = RevisionSelection("a", "b", "b")

        assertEquals(RevisionSelection.EMPTY, pair.click(null, additive = false))
        assertEquals(pair, pair.click(null, additive = true))
    }

    @Test fun `context click keeps pair only for selected nodes`() {
        val pair = RevisionSelection("a", "b", "b")

        assertEquals(RevisionSelection("a", "b", "a"), pair.contextClick("a"))
        assertEquals(pair, pair.contextClick("b"))
        assertEquals(RevisionSelection("c", activeHash = "c"), pair.contextClick("c"))
    }

    @Test fun `refresh retains valid hashes and promotes surviving target`() {
        val pair = RevisionSelection("a", "b", "a")

        assertEquals(pair, pair.retain(setOf("a", "b", "c")))
        assertEquals(RevisionSelection("b", activeHash = "b"), pair.retain(setOf("b", "c")))
        assertEquals(RevisionSelection("a", activeHash = "a"), pair.retain(setOf("a", "c")))
        assertEquals(RevisionSelection.EMPTY, pair.retain(setOf("c")))
    }

    @Test fun `preferred comparison name uses current local branch first`() {
        val snapshot = snapshot(
            HeadState("a", "test", false),
            RevisionRef("refs/heads/another", "a", RefKind.LOCAL_BRANCH),
            RevisionRef("refs/remotes/origin/test", "a", RefKind.REMOTE_BRANCH),
            RevisionRef("refs/tags/v1.0", "a", RefKind.TAG),
            RevisionRef("refs/heads/test", "a", RefKind.LOCAL_BRANCH),
        )

        assertEquals(CompareRevision("a", "test"), preferredCompareRevision(snapshot, "a"))
    }

    @Test fun `preferred comparison name falls back through branch tag and hash`() {
        val remote = snapshot(
            HeadState(null, null, false),
            RevisionRef("refs/tags/v1.0", "a", RefKind.TAG),
            RevisionRef("refs/remotes/origin/test", "a", RefKind.REMOTE_BRANCH),
        )
        val tag = snapshot(HeadState(null, null, false), RevisionRef("refs/tags/v1.0", "a", RefKind.TAG))
        val stash = snapshot(HeadState(null, null, false), RevisionRef("refs/stash", "a", RefKind.STASH))

        assertEquals(CompareRevision("a", "origin/test"), preferredCompareRevision(remote, "a"))
        assertEquals(CompareRevision("a", "v1.0"), preferredCompareRevision(tag, "a"))
        assertEquals(CompareRevision("a", "a"), preferredCompareRevision(stash, "a"))
    }

    @Test fun `native log uses readable ref only while it still points to selected commit`() {
        val selected = CompareRevision("abc123", "origin/test")

        assertEquals(RevisionLogTarget.Reference("origin/test"), revisionLogTarget(selected, "ABC123"))
        assertEquals(RevisionLogTarget.Commit("abc123"), revisionLogTarget(selected, "def456"))
        assertEquals(RevisionLogTarget.Commit("abc123"), revisionLogTarget(selected, null))
        assertEquals(RevisionLogTarget.Commit("abc123"), revisionLogTarget(CompareRevision("abc123", "abc123"), "abc123"))
    }

    @Test fun `copy uses all distinct display refs and falls back to full hash`() {
        val refs = listOf(
            RevisionRef("refs/heads/test", "abc123", RefKind.LOCAL_BRANCH),
            RevisionRef("refs/remotes/origin/test", "abc123", RefKind.REMOTE_BRANCH),
            RevisionRef("refs/heads/test", "abc123", RefKind.LOCAL_BRANCH),
        )

        assertEquals("test\norigin/test", copyableRevisionText(refs, "abc123"))
        assertEquals("abc123", copyableRevisionText(emptyList(), "abc123"))
    }

    @Test fun `head display prefers branch and describes detached head`() {
        assertEquals("test", headDisplayName(HeadState("abc123456789", "test", false)))
        assertEquals("HEAD · abc12345", headDisplayName(HeadState("abc123456789", null, true)))
        assertEquals("HEAD", headDisplayName(null))
    }

    @Test fun `checkout is offered for non-current branches and tags only`() {
        val head = HeadState("a", "test", false)

        assertFalse(checkoutAvailable(RevisionRef("refs/heads/test", "a", RefKind.LOCAL_BRANCH), head))
        assertTrue(checkoutAvailable(RevisionRef("refs/heads/other", "b", RefKind.LOCAL_BRANCH), head))
        assertTrue(checkoutAvailable(RevisionRef("refs/remotes/origin/test", "a", RefKind.REMOTE_BRANCH), head))
        assertTrue(checkoutAvailable(RevisionRef("refs/tags/v1.0", "a", RefKind.TAG), head))
        assertFalse(checkoutAvailable(RevisionRef("refs/stash", "a", RefKind.STASH), head))
    }

    @Test fun `head focus happens for first view root changes and head changes only`() {
        val rootA = Path.of("/repo-a")
        val rootB = Path.of("/repo-b")
        val test = HeadState("a", "test", false)
        val otherAtSameCommit = HeadState("a", "other", false)
        val advanced = HeadState("b", "test", false)

        assertTrue(shouldFocusHead(null, null, rootA, test))
        assertFalse(shouldFocusHead(rootA, test, rootA, test))
        assertTrue(shouldFocusHead(rootA, test, rootB, test))
        assertTrue(shouldFocusHead(rootA, test, rootA, otherAtSameCommit))
        assertTrue(shouldFocusHead(rootA, test, rootA, advanced))
        assertFalse(shouldFocusHead(rootA, test, rootA, HeadState(null, null, false)))
    }

    private fun snapshot(head: HeadState, vararg refs: RevisionRef) = GraphSnapshot(
        commits = listOf(CommitNode("a", emptyList(), 0, "subject")),
        refsByCommit = refs.groupBy { it.target },
        head = head,
    )
}
