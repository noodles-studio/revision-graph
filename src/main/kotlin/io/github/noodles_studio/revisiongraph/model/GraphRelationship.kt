package io.github.noodles_studio.revisiongraph.model

data class RevisionRelationship(
    val basePath: Set<EdgeKey>,
    val targetPath: Set<EdgeKey>,
)

fun GraphSnapshot.relationship(base: String, target: String): RevisionRelationship? {
    if (base !in commitsByHash || target !in commitsByHash) return null
    if (base == target) return RevisionRelationship(emptySet(), emptySet())

    val baseDistances = ancestorDistances(base)
    val targetDistances = ancestorDistances(target)
    val common = baseDistances.keys intersect targetDistances.keys
    val bestCommon = common - dominatedCommonAncestors(common)
    val order = commits.withIndex().associate { it.value.hash to it.index }
    val commonAncestor = bestCommon.minWithOrNull(
        compareBy<String> { maxOf(baseDistances.getValue(it), targetDistances.getValue(it)) }
            .thenBy { baseDistances.getValue(it) + targetDistances.getValue(it) }
            .thenBy { order[it] ?: Int.MAX_VALUE },
    )
    return RevisionRelationship(
        basePath = shortestPathToAncestor(base, commonAncestor),
        targetPath = shortestPathToAncestor(target, commonAncestor),
    )
}

private fun GraphSnapshot.ancestorDistances(start: String): Map<String, Int> {
    val distances = linkedMapOf(start to 0)
    val queue = ArrayDeque<String>()
    queue += start
    while (queue.isNotEmpty()) {
        val child = queue.removeFirst()
        val distance = distances.getValue(child) + 1
        commitsByHash[child]?.parents.orEmpty().filter { it in commitsByHash }.forEach { parent ->
            val previous = distances[parent]
            if (previous == null || distance < previous) {
                distances[parent] = distance
                queue += parent
            }
        }
    }
    return distances
}

private fun GraphSnapshot.dominatedCommonAncestors(common: Set<String>): Set<String> {
    val dominated = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    common.forEach { hash ->
        commitsByHash[hash]?.parents.orEmpty().filter { it in commitsByHash }.forEach { parent ->
            if (visited.add(parent)) queue += parent
        }
    }
    while (queue.isNotEmpty()) {
        val hash = queue.removeFirst()
        if (hash in common) dominated += hash
        commitsByHash[hash]?.parents.orEmpty().filter { it in commitsByHash }.forEach { parent ->
            if (visited.add(parent)) queue += parent
        }
    }
    return dominated
}

private fun GraphSnapshot.shortestPathToAncestor(start: String, ancestor: String?): Set<EdgeKey> {
    val destination: String = ancestor ?: return emptySet()
    if (start == destination) return emptySet()
    val childByParent = mutableMapOf<String, String>()
    val visited = mutableSetOf(start)
    val queue = ArrayDeque<String>()
    queue += start
    while (queue.isNotEmpty() && destination !in visited) {
        val child = queue.removeFirst()
        commitsByHash[child]?.parents.orEmpty().filter { it in commitsByHash }.forEach { parent ->
            if (visited.add(parent)) {
                childByParent[parent] = child
                queue += parent
            }
        }
    }
    if (destination !in visited) return emptySet()

    val result = linkedSetOf<EdgeKey>()
    var parent = destination
    while (parent != start) {
        val child = childByParent[parent] ?: return emptySet()
        result += EdgeKey(child, parent)
        parent = child
    }
    return result
}
