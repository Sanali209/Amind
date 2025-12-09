package com.mindmap.android.utils

import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import kotlin.math.cos
import kotlin.math.sin

object MindMapLayout {

    private const val LEVEL_DISTANCE = 300f // Distance between levels

    fun layout(mindMap: MindMap) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return

        // First pass: Calculate subtree weights (number of leaves)
        val weights = mutableMapOf<String, Int>()
        calculateWeights(root, mindMap, weights)

        // Second pass: Position nodes
        // Root is at 0,0
        root.x = 0f
        root.y = 0f

        // Initial angle range for children: 0 to 2*PI
        var currentAngle = 0.0
        val totalWeight = weights[root.id] ?: 1

        for (childId in root.children) {
            val child = mindMap.nodes[childId] ?: continue
            val childWeight = weights[childId] ?: 1

            // Allocate sector based on weight
            val sweep = (childWeight.toDouble() / totalWeight) * 2 * Math.PI
            val midAngle = currentAngle + sweep / 2

            layoutNode(child, mindMap, weights, midAngle, sweep, 1)

            currentAngle += sweep
        }
    }

    private fun calculateWeights(
        node: MindMapNode,
        mindMap: MindMap,
        weights: MutableMap<String, Int>
    ): Int {
        if (node.children.isEmpty()) {
            weights[node.id] = 1
            return 1
        }

        var sum = 0
        for (childId in node.children) {
            val child = mindMap.nodes[childId]
            if (child != null) {
                sum += calculateWeights(child, mindMap, weights)
            }
        }
        weights[node.id] = sum
        return sum
    }

    private fun layoutNode(
        node: MindMapNode,
        mindMap: MindMap,
        weights: MutableMap<String, Int>,
        angle: Double, // The center angle for this node
        sweep: Double, // The total sector width available for this node's children
        depth: Int
    ) {
        val dist = depth * LEVEL_DISTANCE
        node.x = (cos(angle) * dist).toFloat()
        node.y = (sin(angle) * dist).toFloat()

        if (node.children.isEmpty()) return

        // Distribute children within the available sweep, centered around 'angle'
        // The children span from (angle - sweep/2) to (angle + sweep/2)

        val totalWeight = weights[node.id] ?: 1
        var currentStartAngle = angle - sweep / 2

        for (childId in node.children) {
            val child = mindMap.nodes[childId] ?: continue
            val childWeight = weights[childId] ?: 1

            val childSweep = (childWeight.toDouble() / totalWeight) * sweep
            val childMidAngle = currentStartAngle + childSweep / 2

            layoutNode(child, mindMap, weights, childMidAngle, childSweep, depth + 1)

            currentStartAngle += childSweep
        }
    }
}
