package io.github.noodles_studio.revisiongraph.layout

import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.PriorityQueue
import kotlin.math.max

data class NodeLayout(val hash: String, val bounds: Rectangle2D.Double, val lane: Int, val row: Int)
data class EdgeLayout(val child: String, val parent: String, val points: List<Point2D.Double>)

class SpatialIndex(private val cellSize: Double, nodes: Collection<NodeLayout>) {
    private val cells = mutableMapOf<Pair<Int, Int>, MutableList<NodeLayout>>()
    init { nodes.forEach { node -> keys(node.bounds).forEach { cells.getOrPut(it) { mutableListOf() } += node } } }

    fun query(area: Rectangle2D): List<NodeLayout> = keys(area).asSequence()
        .flatMap { cells[it].orEmpty().asSequence() }.distinctBy { it.hash }
        .filter { it.bounds.intersects(area) }.toList()

    fun hit(point: Point2D): NodeLayout? = query(Rectangle2D.Double(point.x - 1, point.y - 1, 2.0, 2.0))
        .firstOrNull { it.bounds.contains(point) }

    private fun keys(r: Rectangle2D): Sequence<Pair<Int, Int>> = sequence {
        val x0 = kotlin.math.floor(r.minX / cellSize).toInt(); val x1 = kotlin.math.floor(r.maxX / cellSize).toInt()
        val y0 = kotlin.math.floor(r.minY / cellSize).toInt(); val y1 = kotlin.math.floor(r.maxY / cellSize).toInt()
        for (x in x0..x1) for (y in y0..y1) yield(x to y)
    }
}

data class GraphLayout(
    val nodes: List<NodeLayout>,
    val edges: List<EdgeLayout>,
    val bounds: Rectangle2D.Double,
    val index: SpatialIndex,
) {
    val byHash = nodes.associateBy { it.hash }
}

/**
 * Deterministic, pure-JVM layered DAG layout inspired by the original RevisionGraph's
 * Sugiyama pipeline. Long edges receive virtual vertices, layers are reordered with
 * weighted median sweeps, and coordinates are compacted from measured node bounds.
 */
internal class LayeredDagLayoutEngine(
    private val nodeGap: Double = 25.0,
    private val layerGap: Double = 30.0,
    private val dummyGap: Double = 14.0,
    private val componentGap: Double = 72.0,
    private val graphPadding: Double = 24.0,
    private val textMetrics: GraphTextMetrics = EstimatedGraphTextMetrics,
) {
    fun layout(snapshot: GraphSnapshot, cancelled: () -> Boolean = { false }): GraphLayout {
        val ordered = normalize(snapshot, cancelled)
        if (ordered.isEmpty()) {
            val nodes = emptyList<NodeLayout>()
            return GraphLayout(nodes, emptyList(), Rectangle2D.Double(0.0, 0.0, 1.0, 1.0), SpatialIndex(256.0, nodes))
        }

        val loaded = snapshot.commitsByHash.keys
        val orderByHash = ordered.withIndex().associate { it.value.hash to it.index }
        val globalRanks = assignRanks(ordered, loaded, cancelled)
        val stableLanes = assignStableLanes(ordered, loaded, globalRanks, cancelled)
        val components = weakComponents(ordered, loaded, cancelled).sortedWith(
            compareBy<Set<String>> { if (snapshot.head.hash in it) 0 else 1 }
                .thenBy { component -> component.minOf { orderByHash.getValue(it) } },
        )

        val allNodes = mutableListOf<NodeLayout>()
        val allEdges = mutableListOf<EdgeLayout>()
        var componentX = graphPadding
        var graphHeight = 1.0
        components.forEach { hashes ->
            checkCancelled(cancelled)
            val component = layoutComponent(snapshot, ordered, hashes, orderByHash, stableLanes, cancelled)
            component.nodes.forEach { node ->
                allNodes += node.copy(bounds = translated(node.bounds, componentX, graphPadding))
            }
            component.edges.forEach { edge ->
                allEdges += edge.copy(points = edge.points.map { Point2D.Double(it.x + componentX, it.y + graphPadding) })
            }
            componentX += component.width + componentGap
            graphHeight = max(graphHeight, component.height + graphPadding * 2.0)
        }

        val graphWidth = max(1.0, componentX - componentGap + graphPadding)
        return GraphLayout(
            allNodes,
            allEdges,
            Rectangle2D.Double(0.0, 0.0, graphWidth, graphHeight),
            SpatialIndex(256.0, allNodes),
        )
    }

    private data class NodeSize(val width: Double, val height: Double)

    private data class Vertex(
        val id: String,
        val hash: String?,
        val rank: Int,
        val width: Double,
        val height: Double,
        val seed: Double,
        val stableOrder: Int,
        var x: Double = 0.0,
        var y: Double = 0.0,
    ) {
        val dummy get() = hash == null
    }

    private data class Segment(val upper: String, val lower: String, val weight: Int)
    private data class RoutedEdge(val child: String, val parent: String, val vertices: List<String>)
    private data class Neighbor(val id: String, val weight: Int)
    private data class ComponentLayout(val nodes: List<NodeLayout>, val edges: List<EdgeLayout>, val width: Double, val height: Double)

    private fun layoutComponent(
        snapshot: GraphSnapshot,
        ordered: List<CommitNode>,
        hashes: Set<String>,
        orderByHash: Map<String, Int>,
        stableLanes: Map<String, Int>,
        cancelled: () -> Boolean,
    ): ComponentLayout {
        val commits = ordered.filter { it.hash in hashes }
        val ranks = assignRanks(commits, hashes, cancelled)
        val vertices = LinkedHashMap<String, Vertex>()
        commits.forEach { commit ->
            val size = measureNode(snapshot, commit.hash)
            val id = realId(commit.hash)
            vertices[id] = Vertex(
                id,
                commit.hash,
                ranks.getValue(commit.hash),
                size.width,
                size.height,
                stableLanes.getValue(commit.hash).toDouble(),
                orderByHash.getValue(commit.hash),
            )
        }

        val segments = mutableListOf<Segment>()
        val routes = mutableListOf<RoutedEdge>()
        var edgeIndex = 0
        commits.forEach { child ->
            child.parents.forEachIndexed { parentIndex, parent ->
                if (parent !in hashes) return@forEachIndexed
                checkCancelled(cancelled)
                val childRank = ranks.getValue(child.hash)
                val parentRank = ranks.getValue(parent)
                val childLane = stableLanes.getValue(child.hash).toDouble()
                val parentLane = stableLanes.getValue(parent).toDouble()
                val ids = mutableListOf(realId(child.hash))
                for (rank in childRank + 1 until parentRank) {
                    val progress = (rank - childRank).toDouble() / (parentRank - childRank).toDouble()
                    val id = "d:$edgeIndex:$rank"
                    vertices[id] = Vertex(
                        id,
                        null,
                        rank,
                        0.0,
                        0.0,
                        childLane + (parentLane - childLane) * progress,
                        orderByHash.getValue(child.hash) * 1_000 + edgeIndex,
                    )
                    ids += id
                }
                ids += realId(parent)
                val weight = if (parentIndex == 0) 2 else 1
                ids.zipWithNext().forEach { (upper, lower) -> segments += Segment(upper, lower, weight) }
                routes += RoutedEdge(child.hash, parent, ids)
                edgeIndex++
            }
        }

        val maxRank = vertices.values.maxOf { it.rank }
        val layers = MutableList(maxRank + 1) { mutableListOf<Vertex>() }
        vertices.values.forEach { layers[it.rank] += it }
        layers.forEach { layer -> layer.sortWith(compareBy<Vertex> { it.seed }.thenBy { it.stableOrder }.thenBy { it.id }) }

        val above = mutableMapOf<String, MutableList<Neighbor>>()
        val below = mutableMapOf<String, MutableList<Neighbor>>()
        segments.forEach { segment ->
            above.getOrPut(segment.lower) { mutableListOf() } += Neighbor(segment.upper, segment.weight)
            below.getOrPut(segment.upper) { mutableListOf() } += Neighbor(segment.lower, segment.weight)
        }
        minimizeCrossings(layers, above, below, cancelled)
        assignCoordinates(layers, vertices, above, below, cancelled)

        val rankHeights = DoubleArray(layers.size) { rank -> layers[rank].maxOfOrNull { it.height } ?: 0.0 }
        val rankTops = DoubleArray(layers.size)
        var yCursor = 0.0
        rankHeights.forEachIndexed { rank, height ->
            rankTops[rank] = yCursor
            yCursor += height
            if (rank != rankHeights.lastIndex) yCursor += layerGap
        }
        layers.flatten().forEach { vertex -> vertex.y = rankTops[vertex.rank] + rankHeights[vertex.rank] / 2.0 }

        val minX = vertices.values.minOf { it.x - it.width / 2.0 }
        val maxX = vertices.values.maxOf { it.x + it.width / 2.0 }
        val xShift = -minX
        val nodes = vertices.values.mapNotNull { vertex ->
            val hash = vertex.hash ?: return@mapNotNull null
            val bounds = Rectangle2D.Double(
                vertex.x - vertex.width / 2.0 + xShift,
                rankTops[vertex.rank] + (rankHeights[vertex.rank] - vertex.height) / 2.0,
                vertex.width,
                vertex.height,
            )
            NodeLayout(hash, bounds, stableLanes.getValue(hash), vertex.rank)
        }
        val edges = routes.map { route ->
            EdgeLayout(route.child, route.parent, routePoints(route.vertices, vertices, xShift))
        }
        return ComponentLayout(nodes, edges, max(1.0, maxX - minX), max(1.0, yCursor))
    }

    private fun minimizeCrossings(
        layers: List<MutableList<Vertex>>,
        above: Map<String, List<Neighbor>>,
        below: Map<String, List<Neighbor>>,
        cancelled: () -> Boolean,
    ) {
        repeat(8) {
            for (rank in 1..layers.lastIndex) {
                checkCancelled(cancelled)
                reorderLayer(layers[rank], layers[rank - 1], above)
            }
            for (rank in layers.lastIndex - 1 downTo 0) {
                checkCancelled(cancelled)
                reorderLayer(layers[rank], layers[rank + 1], below)
            }
        }
    }

    private fun reorderLayer(layer: MutableList<Vertex>, adjacent: List<Vertex>, neighbors: Map<String, List<Neighbor>>) {
        if (layer.size < 2) return
        val adjacentPositions = adjacent.withIndex().associate { it.value.id to it.index.toDouble() }
        val oldPositions = layer.withIndex().associate { it.value.id to it.index }
        layer.sortWith(
            compareBy<Vertex> { vertex -> weightedMedian(neighbors[vertex.id].orEmpty(), adjacentPositions) ?: oldPositions.getValue(vertex.id).toDouble() }
                .thenBy { oldPositions.getValue(it.id) },
        )
    }

    private fun weightedMedian(neighbors: List<Neighbor>, positions: Map<String, Double>): Double? {
        val values = neighbors.flatMap { neighbor -> List(neighbor.weight) { positions[neighbor.id] ?: return@flatMap emptyList() } }.sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun assignCoordinates(
        layers: List<MutableList<Vertex>>,
        vertices: Map<String, Vertex>,
        above: Map<String, List<Neighbor>>,
        below: Map<String, List<Neighbor>>,
        cancelled: () -> Boolean,
    ) {
        layers.forEach { layer -> packInitial(layer) }
        repeat(12) { iteration ->
            for (rank in 1..layers.lastIndex) {
                checkCancelled(cancelled)
                placeLayer(layers[rank], above, vertices, leftToRight = iteration % 2 == 0)
            }
            for (rank in layers.lastIndex - 1 downTo 0) {
                checkCancelled(cancelled)
                placeLayer(layers[rank], below, vertices, leftToRight = iteration % 2 != 0)
            }
        }
    }

    private fun packInitial(layer: List<Vertex>) {
        if (layer.isEmpty()) return
        layer.first().x = layer.first().width / 2.0
        for (index in 1..layer.lastIndex) {
            layer[index].x = layer[index - 1].x + centerDistance(layer[index - 1], layer[index])
        }
    }

    private fun placeLayer(
        layer: List<Vertex>,
        neighbors: Map<String, List<Neighbor>>,
        vertices: Map<String, Vertex>,
        leftToRight: Boolean,
    ) {
        if (layer.isEmpty()) return
        val desired = DoubleArray(layer.size) { index ->
            val values = neighbors[layer[index].id].orEmpty().flatMap { neighbor ->
                val x = vertices[neighbor.id]?.x ?: return@flatMap emptyList()
                List(neighbor.weight) { x }
            }
            if (values.isEmpty()) layer[index].x else values.average()
        }
        val placed = DoubleArray(layer.size)
        if (leftToRight) {
            placed[0] = desired[0]
            for (index in 1..layer.lastIndex) {
                placed[index] = max(desired[index], placed[index - 1] + centerDistance(layer[index - 1], layer[index]))
            }
        } else {
            placed[layer.lastIndex] = desired.last()
            for (index in layer.lastIndex - 1 downTo 0) {
                placed[index] = minOf(desired[index], placed[index + 1] - centerDistance(layer[index], layer[index + 1]))
            }
        }
        val correction = desired.average() - placed.average()
        layer.indices.forEach { index -> layer[index].x = placed[index] + correction }
    }

    private fun centerDistance(left: Vertex, right: Vertex): Double {
        val gap = if (left.dummy && right.dummy) dummyGap else nodeGap
        return left.width / 2.0 + right.width / 2.0 + gap
    }

    private fun routePoints(
        ids: List<String>,
        vertices: Map<String, Vertex>,
        xShift: Double,
    ): List<Point2D.Double> {
        val points = ids.mapTo(mutableListOf()) { id ->
            val vertex = vertices.getValue(id)
            Point2D.Double(vertex.x + xShift, vertex.y)
        }
        if (points.size < 2) return points

        // Match the original RevisionGraph: OGDF's real/dummy vertex centers form a
        // normal polyline, while only the two real endpoints are clipped to the boxes.
        points[0] = cutPoint(vertices.getValue(ids.first()), points[1], xShift)
        points[points.lastIndex] = cutPoint(vertices.getValue(ids.last()), points[points.lastIndex - 1], xShift)
        return points
    }

    private fun cutPoint(vertex: Vertex, toward: Point2D.Double, xShift: Double): Point2D.Double {
        val centerX = vertex.x + xShift
        val centerY = vertex.y
        val dx = toward.x - centerX
        val dy = toward.y - centerY
        if (dx == 0.0 && dy == 0.0) return Point2D.Double(centerX, centerY)

        val halfWidth = vertex.width / 2.0 + 0.5
        val halfHeight = vertex.height / 2.0 + 0.5
        val xScale = if (dx == 0.0) Double.POSITIVE_INFINITY else halfWidth / kotlin.math.abs(dx)
        val yScale = if (dy == 0.0) Double.POSITIVE_INFINITY else halfHeight / kotlin.math.abs(dy)
        val scale = minOf(xScale, yScale)
        return Point2D.Double(centerX + dx * scale, centerY + dy * scale)
    }

    private fun weakComponents(ordered: List<CommitNode>, loaded: Set<String>, cancelled: () -> Boolean): List<Set<String>> {
        val adjacency = loaded.associateWith { linkedSetOf<String>() }.toMutableMap()
        ordered.forEach { child ->
            child.parents.filter { it in loaded }.forEach { parent ->
                adjacency.getValue(child.hash) += parent
                adjacency.getValue(parent) += child.hash
            }
        }
        val remaining = LinkedHashSet(ordered.map { it.hash })
        val result = mutableListOf<Set<String>>()
        while (remaining.isNotEmpty()) {
            checkCancelled(cancelled)
            val component = linkedSetOf<String>()
            val queue = ArrayDeque<String>()
            queue += remaining.first()
            while (queue.isNotEmpty()) {
                checkCancelled(cancelled)
                val hash = queue.removeFirst()
                if (!component.add(hash)) continue
                remaining.remove(hash)
                adjacency.getValue(hash).forEach { if (it !in component) queue += it }
            }
            result += component
        }
        return result
    }

    private fun measureNode(snapshot: GraphSnapshot, hash: String): NodeSize {
        val refs = snapshot.refsByCommit[hash].orEmpty()
        val detachedHeadRows = if (snapshot.head.hash == hash && snapshot.head.detached) 1 else 0
        val rows = refs.size + detachedHeadRows
        val labels = buildList<Pair<String, Boolean>> {
            if (detachedHeadRows == 1) add(message("node.head.detached") to true)
            addAll(refs.map { ref ->
                val head = snapshot.head.hash == hash && snapshot.head.branch == ref.displayName
                (if (head) "HEAD · ${ref.displayName}" else ref.displayName) to head
            })
        }
        val width = textMetrics.nodeWidth(labels)
        val height = textMetrics.rowHeight() * max(1, rows)
        return NodeSize(width, height)
    }

    private fun assignRanks(ordered: List<CommitNode>, loaded: Set<String>, cancelled: () -> Boolean): Map<String, Int> {
        val ranks = ordered.associate { it.hash to 0 }.toMutableMap()
        ordered.forEach { child ->
            checkCancelled(cancelled)
            val nextRank = ranks.getValue(child.hash) + 1
            child.parents.filter { it in loaded }.forEach { parent ->
                ranks[parent] = max(ranks.getValue(parent), nextRank)
            }
        }
        return ranks
    }

    private fun assignStableLanes(
        ordered: List<CommitNode>,
        loaded: Set<String>,
        ranks: Map<String, Int>,
        cancelled: () -> Boolean,
    ): Map<String, Int> {
        val active = mutableListOf<String?>()
        val result = LinkedHashMap<String, Int>(ordered.size)
        val usedByRank = mutableMapOf<Int, MutableSet<Int>>()
        ordered.forEach { commit ->
            checkCancelled(cancelled)
            var lane = active.indexOf(commit.hash)
            if (lane < 0) lane = active.indexOf(null).takeIf { it >= 0 } ?: active.size.also { active += null }
            val used = usedByRank.getOrPut(ranks.getValue(commit.hash)) { mutableSetOf() }
            while (lane in used || active.getOrNull(lane)?.let { it != commit.hash } == true) {
                lane++
                if (lane == active.size) active += null
            }
            used += lane
            result[commit.hash] = lane
            val parents = commit.parents.filter { it in loaded }
            active[lane] = parents.firstOrNull()
            parents.drop(1).forEachIndexed { index, parent ->
                if (parent !in active) active.add((lane + index + 1).coerceAtMost(active.size), parent)
            }
            while (active.isNotEmpty() && active.last() == null) active.removeLast()
        }
        return result
    }

    private fun normalize(snapshot: GraphSnapshot, cancelled: () -> Boolean): List<CommitNode> {
        val original = snapshot.commits.withIndex().associate { it.value.hash to it.index }
        val byHash = snapshot.commitsByHash
        val incomingChildren = byHash.keys.associateWith { 0 }.toMutableMap()
        snapshot.commits.forEach { child -> child.parents.filter { it in byHash }.forEach { incomingChildren[it] = incomingChildren.getValue(it) + 1 } }
        val ready = PriorityQueue<String>(compareBy { original[it] ?: Int.MAX_VALUE })
        incomingChildren.filterValues { it == 0 }.keys.forEach(ready::add)
        val result = mutableListOf<CommitNode>()
        while (ready.isNotEmpty()) {
            checkCancelled(cancelled)
            val hash = ready.remove(); val node = byHash.getValue(hash); result += node
            node.parents.filter { it in byHash }.forEach { parent ->
                val left = incomingChildren.getValue(parent) - 1; incomingChildren[parent] = left
                if (left == 0) ready += parent
            }
        }
        require(result.size == snapshot.commits.size) { "Commit graph contains a cycle or duplicate hash" }
        return result
    }

    private fun translated(rect: Rectangle2D.Double, x: Double, y: Double) =
        Rectangle2D.Double(rect.x + x, rect.y + y, rect.width, rect.height)

    private fun realId(hash: String) = "r:$hash"

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedException("Layout cancelled")
    }
}
