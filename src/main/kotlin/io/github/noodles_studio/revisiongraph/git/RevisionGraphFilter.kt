package io.github.noodles_studio.revisiongraph.git

internal data class RevisionGraphFilter(
    val excludedRevisions: List<String> = emptyList(),
    val includedRevisions: List<String> = emptyList(),
    val currentBranchOnly: Boolean = false,
    val localBranchesOnly: Boolean = false,
) {
    init {
        require(!currentBranchOnly || !localBranchesOnly) {
            "Current-branch and local-branches filters are mutually exclusive"
        }
    }

    val isActive: Boolean
        get() = excludedRevisions.isNotEmpty() || includedRevisions.isNotEmpty() || currentBranchOnly || localBranchesOnly

    val revisionsToValidate: List<String>
        get() = buildList {
            addAll(excludedRevisions)
            addAll(includedRevisions)
            if (currentBranchOnly) add("HEAD")
        }.distinct()

    fun revisionArguments(): List<String> = buildList {
        when {
            currentBranchOnly -> add("HEAD")
            localBranchesOnly -> add("--branches")
            includedRevisions.isNotEmpty() -> addAll(includedRevisions)
            else -> add("--all")
        }
        excludedRevisions.forEach { add("^$it") }
    }

    companion object {
        val NONE = RevisionGraphFilter()
    }
}

internal fun parseRevisionList(value: String): List<String> =
    Regex("\\S+").findAll(value).map { it.value }.distinct().toList()
