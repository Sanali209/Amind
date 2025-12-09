package com.mindmap.android.utils

import android.graphics.Paint
import android.graphics.Rect
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

object MindMapLayout {

    private const val LEVEL_DISTANCE_BASE = 300f // Base distance
    private const val MIN_NODE_WIDTH = 100f
    private const val MIN_NODE_HEIGHT = 60f
    private const val PADDING = 20f
    private const val TAG_HEIGHT = 30f
    private const val GAP = 10f

    // Text measurement tools (passed or created)
    // Since this is object, we can't easily inject context, but we can accept Paint
    fun layout(mindMap: MindMap, textPaint: Paint, tagPaint: Paint) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return

        // 0. Pre-calculate sizes for all nodes
        mindMap.nodes.values.forEach { node ->
            calculateNodeSize(node, textPaint, tagPaint)
        }

        // 1. First pass: Calculate subtree weights (number of leaves)
        val weights = mutableMapOf<String, Int>()
        calculateWeights(root, mindMap, weights)

        // 2. Second pass: Position nodes (Radial)
        root.x = 0f
        root.y = 0f

        var currentAngle = 0.0
        val totalWeight = weights[root.id] ?: 1

        for (childId in root.children) {
            val child = mindMap.nodes[childId] ?: continue
            val childWeight = weights[childId] ?: 1

            val sweep = (childWeight.toDouble() / totalWeight) * 2 * Math.PI
            val midAngle = currentAngle + sweep / 2

            layoutNode(child, mindMap, weights, midAngle, sweep, 1)

            currentAngle += sweep
        }

        // 3. Third pass: Collision Resolution / Repulsion
        resolveCollisions(mindMap)
    }

    private fun calculateNodeSize(node: MindMapNode, textPaint: Paint, tagPaint: Paint) {
        // Measure Main Text
        val textBounds = Rect()
        textPaint.getTextBounds(node.text, 0, node.text.length, textBounds)
        val textWidth = textBounds.width().toFloat()
        val textHeight = textBounds.height().toFloat()

        // Measure Tags
        // We assume tags are in a single row or wrapped?
        // Let's assume a simplified single row for width calculation, or max of text width vs tag width if they were stacked.
        // Actually the prompt implies tags surround text or inside.
        // Let's stack them: Text top, Tags bottom.
        var tagsWidth = 0f
        if (node.tags.isNotEmpty()) {
            val tagPadding = 10f
            node.tags.forEach { tag ->
                 val tBounds = Rect()
                 tagPaint.getTextBounds(tag, 0, tag.length, tBounds)
                 tagsWidth += tBounds.width() + tagPadding * 2 + 10f // + spacing
            }
        }

        val contentWidth = max(textWidth, tagsWidth)
        val contentHeight = textHeight + (if (node.tags.isNotEmpty()) TAG_HEIGHT + GAP else 0f)

        node.width = max(MIN_NODE_WIDTH, contentWidth + PADDING * 2)
        node.height = max(MIN_NODE_HEIGHT, contentHeight + PADDING * 2)
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
        angle: Double,
        sweep: Double,
        depth: Int
    ) {
        // Dynamic distance based on depth is sometimes better, but fixed is okay for now.
        // Maybe increase distance if depth increases to give more circumference?
        val dist = depth * LEVEL_DISTANCE_BASE
        node.x = (cos(angle) * dist).toFloat()
        node.y = (sin(angle) * dist).toFloat()

        if (node.children.isEmpty()) return

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

    private fun resolveCollisions(mindMap: MindMap) {
        // Simple iterative repulsion
        val iterations = 50
        val nodes = mindMap.nodes.values.toList()

        for (i in 0 until iterations) {
            var maxMovement = 0f
            for (n1 in nodes) {
                if (n1.id == mindMap.rootNodeId) continue // Root stays fixed

                for (n2 in nodes) {
                    if (n1 == n2) continue

                    val dx = n1.x - n2.x
                    val dy = n1.y - n2.y
                    val dist = hypot(dx, dy)

                    // Safe distance = sum of radii (approximated by half-diagonal) + buffer
                    // Or axis aligned box check?
                    // Let's use a circle approximation for repulsion for smoothness
                    val r1 = max(n1.width, n1.height) / 2f
                    val r2 = max(n2.width, n2.height) / 2f
                    val minDist = r1 + r2 + 20f // buffer

                    if (dist < minDist && dist > 0.001f) {
                        // Push n1 away from n2
                        val overlap = minDist - dist
                        val pushX = (dx / dist) * overlap * 0.1f // small step
                        val pushY = (dy / dist) * overlap * 0.1f

                        n1.x += pushX
                        n1.y += pushY

                        maxMovement = max(maxMovement, hypot(pushX, pushY))
                    }
                }
            }
            if (maxMovement < 1f) break // Converged
        }
    }
}
