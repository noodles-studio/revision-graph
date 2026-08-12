package io.github.noodles_studio.revisiongraph.layout

import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

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
        val x0 = kotlin.math.floor(r.minX / cellSize).toInt()
        val x1 = kotlin.math.floor(r.maxX / cellSize).toInt()
        val y0 = kotlin.math.floor(r.minY / cellSize).toInt()
        val y1 = kotlin.math.floor(r.maxY / cellSize).toInt()
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
