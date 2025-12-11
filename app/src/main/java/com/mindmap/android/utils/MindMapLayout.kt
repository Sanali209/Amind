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

    private const val LEVEL_DISTANCE_BASE = 300f
    private const val MIN_NODE_WIDTH = 100f
    private const val MIN_NODE_HEIGHT = 60f
    private const val PADDING = 20f
    private const val TAG_HEIGHT = 30f
    private const val GAP = 10f

    fun layout(mindMap: MindMap, textPaint: Paint, tagPaint: Paint) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return

        mindMap.nodes.values.forEach { node ->
            calculateNodeSize(node, textPaint, tagPaint)
        }

        if (mindMap.layoutType == "TREE") {
            layoutTree(mindMap)
        } else {
            layoutRadial(mindMap)
        }

        // Resolve collisions logic can be shared or specific
        // Radial needs it more. Tree usually doesn't if implemented with spacing.
        // We'll run it for both for safety, but maybe relax it for Tree.
        resolveCollisions(mindMap)
    }

    private fun layoutRadial(mindMap: MindMap) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return

        val weights = mutableMapOf<String, Int>()
        calculateWeights(root, mindMap, weights)

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

                layoutNodeRadial(child, mindMap, weights, midAngle, sweep, 1)

                currentAngle += sweep
            }
        }
    }

    private fun layoutNodeRadial(
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

            layoutNodeRadial(child, mindMap, weights, childMidAngle, childSweep, depth + 1)

            currentStartAngle += childSweep
        }
    }

    private fun layoutTree(mindMap: MindMap) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return
        root.x = 0f
        root.y = 0f

        if (root.isCollapsed || root.children.isEmpty()) return

        val mid = root.children.size / 2
        val rightChildren = root.children.subList(mid, root.children.size)
        val leftChildren = root.children.subList(0, mid)

        // Right
        var currentY = -getChildrenHeight(rightChildren, mindMap) / 2
        for (childId in rightChildren) {
             val child = mindMap.nodes[childId] ?: continue
             val h = getSubtreeHeight(child, mindMap)
             layoutNodeTree(child, mindMap, 1, currentY + h/2, 1)
             currentY += h + GAP
        }

        // Left
        currentY = -getChildrenHeight(leftChildren, mindMap) / 2
        for (childId in leftChildren) {
             val child = mindMap.nodes[childId] ?: continue
             val h = getSubtreeHeight(child, mindMap)
             layoutNodeTree(child, mindMap, 1, currentY + h/2, -1)
             currentY += h + GAP
        }
    }

    private fun layoutNodeTree(node: MindMapNode, mindMap: MindMap, depth: Int, y: Float, direction: Int) {
        node.x = direction * depth * 250f
        node.y = y

        if (node.isCollapsed || node.children.isEmpty()) return

        val totalH = getChildrenHeight(node.children, mindMap)
        var startY = y - totalH / 2

        for (childId in node.children) {
            val child = mindMap.nodes[childId] ?: continue
            val h = getSubtreeHeight(child, mindMap)
            layoutNodeTree(child, mindMap, depth + 1, startY + h/2, direction)
            startY += h + GAP
        }
    }

    private fun getSubtreeHeight(node: MindMapNode, mindMap: MindMap): Float {
        if (node.isCollapsed || node.children.isEmpty()) return node.height
        val childrenH = getChildrenHeight(node.children, mindMap)
        return max(node.height, childrenH)
    }

    private fun getChildrenHeight(children: List<String>, mindMap: MindMap): Float {
        var h = 0f
        for (cid in children) {
            val child = mindMap.nodes[cid]
            if (child != null) {
                h += getSubtreeHeight(child, mindMap) + GAP
            }
        }
        return if (h > 0) h else 0f
    }

    private fun calculateNodeSize(node: MindMapNode, textPaint: Paint, tagPaint: Paint) {
        val textBounds = Rect()
        textPaint.getTextBounds(node.text, 0, node.text.length, textBounds)
        val textWidth = textBounds.width().toFloat()
        val textHeight = textBounds.height().toFloat()

        var tagsWidth = 0f
        if (node.tags.isNotEmpty()) {
            val tagPadding = 10f
            node.tags.forEach { tag ->
                 val tBounds = Rect()
                 tagPaint.getTextBounds(tag, 0, tag.length, tBounds)
                 tagsWidth += tBounds.width() + tagPadding * 2 + 10f
            }
        }

        var checkWidth = 0f
        if (node.isTodo) checkWidth = 40f

        var imgHeight = 0f
        if (node.images.isNotEmpty()) imgHeight = 120f

        val contentWidth = max(textWidth, tagsWidth) + checkWidth
        val contentHeight = textHeight + (if (node.tags.isNotEmpty()) TAG_HEIGHT + GAP else 0f) + imgHeight

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
                    val minDist = r1 + r2 + 20f

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
