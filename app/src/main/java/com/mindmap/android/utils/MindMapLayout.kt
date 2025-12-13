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

        // 0. Pre-calculate sizes for nodes
        mindMap.nodes.values.forEach { node ->
            calculateNodeSize(node, textPaint, tagPaint)
        }

        if (mindMap.layoutType == "TREE") {
            layoutTree(mindMap)
        } else {
            // Radial Layout
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
    }

    private fun layoutTree(mindMap: MindMap) {
        val root = mindMap.nodes[mindMap.rootNodeId] ?: return
        root.x = 0f
        root.y = 0f

        if (root.isCollapsed || root.children.isEmpty()) return

        // Split children into Right and Left (simple alternating or split half)
        val rightChildren = mutableListOf<String>()
        val leftChildren = mutableListOf<String>()

        root.children.forEachIndexed { index, id ->
            if (index % 2 == 0) rightChildren.add(id) else leftChildren.add(id)
        }

        // Layout Right Side
        val rightHeight = calculateTreeHeight(rightChildren, mindMap)
        layoutTreeSide(rightChildren, mindMap, 1, -rightHeight / 2)

        // Layout Left Side
        val leftHeight = calculateTreeHeight(leftChildren, mindMap)
        layoutTreeSide(leftChildren, mindMap, -1, -leftHeight / 2)
    }

    private fun calculateTreeHeight(children: List<String>, mindMap: MindMap): Float {
        var h = 0f
        children.forEach { id ->
            h += calculateSubtreeHeight(id, mindMap)
        }
        return h
    }

    private fun calculateSubtreeHeight(nodeId: String, mindMap: MindMap): Float {
        val node = mindMap.nodes[nodeId] ?: return 0f
        if (node.isCollapsed || node.children.isEmpty()) {
            return node.height + GAP
        }
        var childrenHeight = 0f
        node.children.forEach { childId ->
            childrenHeight += calculateSubtreeHeight(childId, mindMap)
        }
        // Height is max of node height or children stack height, but typically children stack + self?
        // Standard tree: node is centered relative to children.
        // Total height allocated is sum of children heights.
        // If children height is small, use node height.
        return max(node.height + GAP, childrenHeight)
    }

    private fun layoutTreeSide(children: List<String>, mindMap: MindMap, direction: Int, startY: Float) {
        var currentY = startY

        children.forEach { nodeId ->
            val node = mindMap.nodes[nodeId] ?: return@forEach
            val nodeH = calculateSubtreeHeight(nodeId, mindMap)

            // Center node in its allocated vertical slot
            val childY = currentY + nodeH / 2

            // X position: parent X + parent Width/2 + GAP + child Width/2 (handled by recursive call passing parent X?)
            // Here we are at level 1 (children of root).
            // Root is at 0,0.
            // Node X:
            val root = mindMap.nodes[mindMap.rootNodeId]!!
            val nodeX = direction * (root.width / 2 + LEVEL_DISTANCE_BASE/2 + node.width / 2) // LEVEL_DISTANCE_BASE used as horizontal gap

            node.x = nodeX
            node.y = childY

            // Recursive for children
            if (!node.isCollapsed && node.children.isNotEmpty()) {
                layoutTreeChildren(node, mindMap, direction)
            }

            currentY += nodeH
        }
    }

    private fun layoutTreeChildren(parent: MindMapNode, mindMap: MindMap, direction: Int) {
        // Parent is already placed at parent.x, parent.y
        // Children should be placed to the right (if dir=1) or left (if dir=-1)
        // Stacked vertically centered on parent.y?
        // No, parent.y was centered on its children.
        // So we start placing children from top of parent's allocated slot?
        // We need to know the startY for children relative to parent center.

        val totalChildrenHeight = calculateTreeHeight(parent.children, mindMap)
        var currentY = parent.y - totalChildrenHeight / 2

        parent.children.forEach { childId ->
             val child = mindMap.nodes[childId] ?: return@forEach
             val childH = calculateSubtreeHeight(childId, mindMap)

             val childY = currentY + childH / 2
             val childX = parent.x + direction * (parent.width / 2 + 100f + child.width / 2) // 100f horizontal gap

             child.x = childX
             child.y = childY

             if (!child.isCollapsed && child.children.isNotEmpty()) {
                 layoutTreeChildren(child, mindMap, direction)
             }

             currentY += childH
        }
    }

    private fun calculateNodeSize(node: MindMapNode, textPaint: Paint, tagPaint: Paint) {
        // Measure Main Text with Wrapping
        val tp = TextPaint(textPaint)

        val textWidth: Float
        val textHeight: Float

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
             val staticLayout = android.text.StaticLayout.Builder.obtain(
                node.text, 0, node.text.length, tp, MAX_TEXT_WIDTH.toInt()
            ).build()
            textWidth = staticLayout.width.toFloat()
            textHeight = staticLayout.height.toFloat()
        } else {
            @Suppress("DEPRECATION")
            val staticLayout = android.text.StaticLayout(
                node.text, tp, MAX_TEXT_WIDTH.toInt(),
                android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
            )
            textWidth = staticLayout.width.toFloat()
            textHeight = staticLayout.height.toFloat()
        }

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
