package com.mindmap.android.utils

import android.graphics.Paint
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

object MindMapLayout {

    private const val LEVEL_DISTANCE_BASE = 400f // Increased from 300f
    private const val MIN_NODE_WIDTH = 150f // Increased minimum width
    private const val MIN_NODE_HEIGHT = 80f
    private const val PADDING = 30f // Increased padding
    private const val TAG_HEIGHT = 30f
    private const val GAP = 10f
    private const val MAX_TEXT_WIDTH = 400f // Max width for wrapping

    // Text measurement tools (passed or created)
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

        if (!root.isCollapsed) {
            for (childId in root.children) {
                val child = mindMap.nodes[childId] ?: continue
                val childWeight = weights[childId] ?: 1

                val sweep = (childWeight.toDouble() / totalWeight) * 2 * Math.PI
                val midAngle = currentAngle + sweep / 2

                layoutNode(child, mindMap, weights, midAngle, sweep, 1)

                currentAngle += sweep
            }
        }

        // 3. Third pass: Collision Resolution / Repulsion
        resolveCollisions(mindMap)
    }

    private fun calculateNodeSize(node: MindMapNode, textPaint: Paint, tagPaint: Paint) {
        // Measure Main Text with Wrapping
        val tp = TextPaint(textPaint)
        val staticLayout = android.text.StaticLayout.Builder.obtain(
            node.text, 0, node.text.length, tp, MAX_TEXT_WIDTH.toInt()
        ).build()

        val textWidth = staticLayout.width.toFloat()
        val textHeight = staticLayout.height.toFloat()

        // Measure Tags
        var tagsWidth = 0f
        if (node.tags.isNotEmpty()) {
            val tagPadding = 10f
            node.tags.forEach { tag ->
                 val tBounds = Rect()
                 tagPaint.getTextBounds(tag, 0, tag.length, tBounds)
                 tagsWidth += tBounds.width() + tagPadding * 2 + 10f
            }
        }

        // Measure Image (Thumbnail placeholder)
        var imgHeight = 0f
        var imgWidth = 0f
        if (node.images.isNotEmpty()) {
            imgHeight = 100f // Hardcoded thumbnail size match Canvas
            imgWidth = 100f
        }

        // Measure Checkbox
        var cbWidth = 0f
        if (node.isTodo) {
            cbWidth = 40f // 30 + padding
        }

        val contentWidth = max(max(textWidth, tagsWidth), imgWidth) + cbWidth
        val contentHeight = textHeight + (if (node.tags.isNotEmpty()) TAG_HEIGHT + GAP else 0f) + (if (imgHeight > 0) imgHeight + GAP else 0f)

        node.width = max(MIN_NODE_WIDTH, contentWidth + PADDING * 2)
        node.height = max(MIN_NODE_HEIGHT, contentHeight + PADDING * 2)
    }

    private fun calculateWeights(
        node: MindMapNode,
        mindMap: MindMap,
        weights: MutableMap<String, Int>
    ): Int {
        if (node.isCollapsed || node.children.isEmpty()) {
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
        val dist = depth * LEVEL_DISTANCE_BASE
        node.x = (cos(angle) * dist).toFloat()
        node.y = (sin(angle) * dist).toFloat()

        if (node.isCollapsed || node.children.isEmpty()) return

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
        val visibleNodes = getVisibleNodes(mindMap)
        val iterations = 50

        for (i in 0 until iterations) {
            var maxMovement = 0f
            for (n1 in visibleNodes) {
                if (n1.id == mindMap.rootNodeId) continue

                for (n2 in visibleNodes) {
                    if (n1 == n2) continue

                    val dx = n1.x - n2.x
                    val dy = n1.y - n2.y
                    val dist = hypot(dx, dy)

                    val r1 = max(n1.width, n1.height) / 2f
                    val r2 = max(n2.width, n2.height) / 2f
                    val minDist = r1 + r2 + 40f // Increased buffer

                    if (dist < minDist && dist > 0.001f) {
                        val overlap = minDist - dist
                        val pushX = (dx / dist) * overlap * 0.1f
                        val pushY = (dy / dist) * overlap * 0.1f

                        n1.x += pushX
                        n1.y += pushY

                        maxMovement = max(maxMovement, hypot(pushX, pushY))
                    }
                }
            }
            if (maxMovement < 1f) break
        }
    }

    private fun getVisibleNodes(mindMap: MindMap): List<MindMapNode> {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return emptyList()
        val list = mutableListOf<MindMapNode>()
        collectVisible(root, mindMap, list)
        return list
    }

    private fun collectVisible(node: MindMapNode, mindMap: MindMap, list: MutableList<MindMapNode>) {
        list.add(node)
        if (!node.isCollapsed) {
            for (childId in node.children) {
                val child = mindMap.nodes[childId]
                if (child != null) {
                    collectVisible(child, mindMap, list)
                }
            }
        }
    }
}
