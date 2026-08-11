package io.github.noodles_studio.revisiongraph.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevisionGraphFilterTest {
    @Test fun `unfiltered graph loads every ref`() {
        val filter = RevisionGraphFilter.NONE

        assertEquals(listOf("--all"), filter.revisionArguments())
        assertFalse(filter.isActive)
    }

    @Test fun `explicit range includes targets and excludes from revisions`() {
        val filter = RevisionGraphFilter(
            excludedRevisions = listOf("release-1", "old-tag"),
            includedRevisions = listOf("feature/a", "feature/b"),
        )

        assertEquals(listOf("feature/a", "feature/b", "^release-1", "^old-tag"), filter.revisionArguments())
        assertTrue(filter.isActive)
    }

    @Test fun `current branch and local branches match original graph scopes`() {
        val current = RevisionGraphFilter(excludedRevisions = listOf("v1.0"), currentBranchOnly = true)

        assertEquals(listOf("HEAD", "^v1.0"), current.revisionArguments())
        assertEquals(listOf("v1.0", "HEAD"), current.revisionsToValidate)
        assertEquals(
            listOf("--branches", "^v1.0"),
            RevisionGraphFilter(excludedRevisions = listOf("v1.0"), localBranchesOnly = true).revisionArguments(),
        )
    }

    @Test fun `revision input accepts original whitespace separated syntax`() {
        assertEquals(listOf("main", "release/v2", "v1.0"), parseRevisionList(" main\nrelease/v2  main\tv1.0 "))
    }
}
