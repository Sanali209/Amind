package com.mindmap.android.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import com.mindmap.android.model.MindMap
import com.mindmap.android.ui.theme.RainbowColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun MindMapCanvas(
    mindMap: MindMap,
    selectedNodeId: String? = null,
    onNodeClick: (String, Offset) -> Unit, // Changed to include Offset
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    onCrossLinkClick: (String, Offset) -> Unit,
    onNodeDrop: (String, String) -> Unit, // draggedId, targetId
    onToggleCollapse: (String) -> Unit, // New Callback
    modifier: Modifier = Modifier
) {
    // Transformations
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    // Dragging State
    var draggingNodeId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) } // Screen coords offset from node center
    var hoverTargetId by remember { mutableStateOf<String?>(null) }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    val tagPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.LEFT
        }
    }

    val notePaint = remember {
        Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 25f
            textAlign = Paint.Align.CENTER
        }
    }

    val collapsePaint = remember {
        Paint().apply {
            color = android.graphics.Color.CYAN
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Custom gesture detector for 1-finger drag (node) vs 2-finger pan/zoom (canvas)
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // Check if hitting a node
                        // Convert touch to canvas coords
                        val canvasX = (down.position.x - size.width / 2 - offset.x) / scale
                        val canvasY = (down.position.y - size.height / 2 - offset.y) / scale

                        // Check collapse button hit first?
                        // Iterate visible nodes to find collapse button hits
                        // Collapse button is drawn at Top-Right or Right side of node?
                        // Let's assume it's a small circle at (node.x + width/2 + 20, node.y)
                        val collapseHitId = findCollapseButtonAt(mindMap, canvasX, canvasY)
                        if (collapseHitId != null) {
                            down.consume()
                            // Just toggle, no drag
                            onToggleCollapse(collapseHitId)
                            return@awaitPointerEventScope
                        }

                        val hitNodeId = findNodeAt(mindMap, canvasX, canvasY)

                        if (hitNodeId != null) {
                            // Potentially dragging node or clicking
                            var moved = false
                            drag(down.id) { change ->
                                val count = currentEvent.changes.size
                                if (count == 1) {
                                    // 1 finger -> Drag Node
                                    change.consume()
                                    draggingNodeId = hitNodeId
                                    moved = true

                                    // Update drag position (visual only)
                                    // We track the delta
                                    dragOffset += change.positionChange()

                                    // Hit test for hover target
                                    // Calculate current finger pos in canvas
                                    val currX = (change.position.x - size.width / 2 - offset.x) / scale
                                    val currY = (change.position.y - size.height / 2 - offset.y) / scale

                                    // Don't target self or descendants (cycle check done here or later?)
                                    // Simple hit test first
                                    val target = findNodeAt(mindMap, currX, currY)
                                    hoverTargetId = if (target != hitNodeId) target else null

                                } else {
                                    // 2+ fingers -> Pan/Zoom
                                    // If we were dragging a node, cancel it?
                                    // Or just process pan/zoom
                                    draggingNodeId = null
                                    hoverTargetId = null

                                    val zoomChange = currentEvent.calculateZoom()
                                    val panChange = currentEvent.calculatePan()

                                    scale *= zoomChange
                                    offset += panChange
                                    change.consume()
                                }
                            }

                            // Drag finished
                            if (draggingNodeId != null && hoverTargetId != null) {
                                onNodeDrop(draggingNodeId!!, hoverTargetId!!)
                            } else if (!moved) {
                                // Click logic - NOW PASSING OFFSET
                                onNodeClick(hitNodeId, down.position)
                            }

                            draggingNodeId = null
                            dragOffset = Offset.Zero
                            hoverTargetId = null

                        } else {
                            // Hit background or crosslink
                            // Wait for potential drag or 2nd finger
                             var moved = false
                             drag(down.id) { change ->
                                 moved = true
                                 val zoomChange = currentEvent.calculateZoom()
                                 val panChange = currentEvent.calculatePan()
                                 scale *= zoomChange
                                 offset += panChange
                                 change.consume()
                             }

                             if (!moved) {
                                 // Check crosslinks
                                 val linkId = findCrossLinkAt(mindMap, canvasX, canvasY)
                                 if (linkId != null) {
                                     onCrossLinkClick(linkId, down.position)
                                 } else {
                                     onBackgroundTap()
                                 }
                             }
                        }
                    }
                }
            }
    ) {
        val centerX = size.width / 2 + offset.x
        val centerY = size.height / 2 + offset.y

        // Recursively draw visible nodes and connections
        // We start from root
        val root = mindMap.nodes[mindMap.rootNodeId]
        if (root != null) {
            drawNodeTree(
                this,
                root,
                mindMap,
                centerX,
                centerY,
                scale,
                draggingNodeId,
                dragOffset,
                hoverTargetId,
                selectedNodeId,
                textPaint,
                tagPaint,
                notePaint,
                collapsePaint
            )
        }

        // Draw Crosslinks (Red, with Arrow, and Label)
        // Only draw links if both start and end are visible (not collapsed)
        mindMap.crossLinks.forEach { link ->
            val start = mindMap.nodes[link.startNodeId]
            val end = mindMap.nodes[link.endNodeId]

            if (start != null && end != null && isVisible(mindMap, start.id) && isVisible(mindMap, end.id)) {
                // Adjust for dragging
                val sDx = if (start.id == draggingNodeId) dragOffset.x else 0f
                val sDy = if (start.id == draggingNodeId) dragOffset.y else 0f
                val eDx = if (end.id == draggingNodeId) dragOffset.x else 0f
                val eDy = if (end.id == draggingNodeId) dragOffset.y else 0f

                val sx = start.x * scale + centerX + sDx
                val sy = start.y * scale + centerY + sDy
                val ex = end.x * scale + centerX + eDx
                val ey = end.y * scale + centerY + eDy

                val startPoint = getRectIntersection(sx, sy, ex, ey, start.width * scale, start.height * scale)
                val endPoint = getRectIntersection(ex, ey, sx, sy, end.width * scale, end.height * scale)

                drawLine(
                    color = Color.Red,
                    start = startPoint,
                    end = endPoint,
                    strokeWidth = 3f * scale
                )

                val angle = atan2(endPoint.y - startPoint.y, endPoint.x - startPoint.x)
                val arrowSize = 20f * scale

                rotate(degrees = Math.toDegrees(angle.toDouble()).toFloat(), pivot = endPoint) {
                    val path = Path().apply {
                        moveTo(endPoint.x, endPoint.y)
                        lineTo(endPoint.x - arrowSize, endPoint.y - arrowSize / 2)
                        lineTo(endPoint.x - arrowSize, endPoint.y + arrowSize / 2)
                        close()
                    }
                    drawPath(path, Color.Red)
                }

                if (!link.label.isNullOrBlank()) {
                     val mx = (startPoint.x + endPoint.x) / 2
                     val my = (startPoint.y + endPoint.y) / 2

                     drawIntoCanvas { canvas ->
                        textPaint.textSize = 30f * scale
                        textPaint.color = android.graphics.Color.RED
                        textPaint.textAlign = Paint.Align.CENTER

                        val boundRect = android.graphics.Rect()
                        textPaint.getTextBounds(link.label!!, 0, link.label!!.length, boundRect)
                        val bgPadding = 5f * scale
                        val bgLeft = mx - boundRect.width()/2 - bgPadding
                        val bgTop = my - boundRect.height() - bgPadding - 10f*scale
                        val bgRight = mx + boundRect.width()/2 + bgPadding
                        val bgBottom = my + bgPadding - 10f*scale

                        val bgPaint = Paint().apply {
                            color = android.graphics.Color.BLACK
                            alpha = 150
                            style = Paint.Style.FILL
                        }
                        canvas.nativeCanvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, bgPaint)

                        canvas.nativeCanvas.drawText(link.label!!, mx, my - 10f * scale, textPaint)
                     }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNodeTree(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    node: com.mindmap.android.model.MindMapNode,
    mindMap: MindMap,
    centerX: Float,
    centerY: Float,
    scale: Float,
    draggingNodeId: String?,
    dragOffset: Offset,
    hoverTargetId: String?,
    selectedNodeId: String?,
    textPaint: Paint,
    tagPaint: Paint,
    notePaint: Paint,
    collapsePaint: Paint
) {
    val isDragging = (node.id == draggingNodeId)
    val dx = if (isDragging) dragOffset.x else 0f
    val dy = if (isDragging) dragOffset.y else 0f

    val nx = node.x * scale + centerX + dx
    val ny = node.y * scale + centerY + dy

    // Draw connections to children first (behind nodes)
    // Only if not collapsed
    if (!node.isCollapsed) {
        node.children.forEach { childId ->
            val child = mindMap.nodes[childId] ?: return@forEach
            // Calculate child pos
            val cx = child.x * scale + centerX
            val cy = child.y * scale + centerY

            // Connection color (inherit or rainbow)
            val color = if (node.colorOverride != null) Color(node.colorOverride!!.toULong())
                        else if (node.id == mindMap.rootNodeId) Color.Gray
                        else RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]

            val path = Path().apply {
                moveTo(nx, ny)
                cubicTo(
                    nx, ny + (cy - ny) / 2,
                    cx, cy - (cy - ny) / 2,
                    cx, cy
                )
            }

            scope.drawPath(
                path = path,
                color = color,
                style = Stroke(width = 5f * scale)
            )

            // Recursive Draw
            drawNodeTree(scope, child, mindMap, centerX, centerY, scale, draggingNodeId, dragOffset, hoverTargetId, selectedNodeId, textPaint, tagPaint, notePaint, collapsePaint)
        }
    }

    // Now Draw Node
    val nWidth = node.width * scale
    val nHeight = node.height * scale
    val left = nx - nWidth / 2
    val top = ny - nHeight / 2

    val isHoverTarget = (node.id == hoverTargetId)
    val isSelected = (node.id == selectedNodeId)

    val nodeColor = if (node.colorOverride != null) {
        Color(node.colorOverride!!.toULong())
    } else if (node.id == mindMap.rootNodeId) {
        Color.White
    } else {
        RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]
    }

    scope.drawRoundRect(
        color = nodeColor,
        topLeft = Offset(left, top),
        size = Size(nWidth, nHeight),
        cornerRadius = CornerRadius(16f * scale, 16f * scale),
        style = Stroke(width = 5f * scale)
    )

    if (isSelected) {
        scope.drawRoundRect(
            color = Color.Cyan,
            topLeft = Offset(left, top),
            size = Size(nWidth, nHeight),
            cornerRadius = CornerRadius(16f * scale, 16f * scale),
            style = Stroke(width = 8f * scale)
        )
    }

    if (isHoverTarget) {
        scope.drawRoundRect(
            color = Color.Green,
            topLeft = Offset(left, top),
            size = Size(nWidth, nHeight),
            cornerRadius = CornerRadius(16f * scale, 16f * scale),
            style = Stroke(width = 8f * scale)
        )
    }

    scope.drawRoundRect(
        color = Color.DarkGray,
        topLeft = Offset(left, top),
        size = Size(nWidth, nHeight),
        cornerRadius = CornerRadius(16f * scale, 16f * scale)
    )

    scope.drawIntoCanvas { canvas ->
        textPaint.textSize = 40f * scale
        textPaint.color = android.graphics.Color.WHITE

        val textYOffset = if (node.tags.isNotEmpty()) -15f * scale else 15f * scale

        canvas.nativeCanvas.drawText(node.text, nx, ny + textYOffset, textPaint)

        if (!node.note.isNullOrBlank()) {
             notePaint.textSize = 25f * scale
             canvas.nativeCanvas.drawText("📝", nx + nWidth/2 - 20f*scale, ny - nHeight/2 + 30f*scale, notePaint)
        }

        if (node.tags.isNotEmpty()) {
            tagPaint.textSize = 30f * scale
            val tagsY = ny + 35f * scale
            val tagsString = node.tags.joinToString(", ") { "#$it" }
            canvas.nativeCanvas.drawText(tagsString, nx, tagsY, tagPaint)
        }

        // Draw Collapse/Expand Button if children exist
        if (node.children.isNotEmpty()) {
            val indicator = if (node.isCollapsed) "+" else "-"
            val btnX = nx + nWidth/2 + 10f*scale
            val btnY = ny
            val btnRadius = 15f * scale

            val paintCircle = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                style = android.graphics.Paint.Style.FILL
            }
            val paintStroke = android.graphics.Paint().apply {
                color = android.graphics.Color.CYAN
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * scale
            }

            canvas.nativeCanvas.drawCircle(btnX, btnY, btnRadius, paintCircle)
            canvas.nativeCanvas.drawCircle(btnX, btnY, btnRadius, paintStroke)

            collapsePaint.textSize = 30f * scale
            // Centering text vertically
            val bounds = android.graphics.Rect()
            collapsePaint.getTextBounds(indicator, 0, indicator.length, bounds)
            val ty = btnY + bounds.height()/2f

            canvas.nativeCanvas.drawText(indicator, btnX, ty, collapsePaint)
        }
    }
}

// Check visibility helper
fun isVisible(mindMap: MindMap, nodeId: String): Boolean {
    // Walk up to root. If any parent is collapsed, then not visible.
    var currentId = nodeId
    while(currentId != mindMap.rootNodeId) {
        val node = mindMap.nodes[currentId] ?: return false
        val parentId = node.parentId ?: return (currentId == mindMap.rootNodeId) // Should not happen for non-root
        val parent = mindMap.nodes[parentId] ?: return false
        if (parent.isCollapsed) return false
        currentId = parentId
    }
    return true
}

private fun getRectIntersection(cx: Float, cy: Float, tx: Float, ty: Float, w: Float, h: Float): Offset {
    val dx = tx - cx
    val dy = ty - cy
    if (dx == 0f && dy == 0f) return Offset(cx, cy)

    val hw = w / 2
    val hh = h / 2

    val tX = if (dx != 0f) {
        val dir = if (dx > 0) 1 else -1
        (dir * hw) / dx
    } else Float.MAX_VALUE

    val tY = if (dy != 0f) {
        val dir = if (dy > 0) 1 else -1
        (dir * hh) / dy
    } else Float.MAX_VALUE

    val t = min(abs(tX), abs(tY))

    return Offset(cx + dx * t, cy + dy * t)
}

// ... helper methods findNodeAt, etc. need update to ignore collapsed children?
// Actually findNodeAt iterates all nodes. We should only hit test visible nodes.
private fun findNodeAt(mindMap: MindMap, x: Float, y: Float): String? {
    // Better: traverse starting from root, respecting collapsed state?
    // Or iterate all and check isVisible?
    // Iterating all is O(N), checking isVisible is O(Depth). Total O(N*Depth).
    // Traversing tree is O(VisibleNodes).
    // Let's iterate all but check visibility quickly.
    // Or just use the getVisibleNodes helper from Layout if we moved it to Model/Utils?
    // For now, simple check:
    for (node in mindMap.nodes.values) {
        if (!isVisible(mindMap, node.id)) continue

        val halfW = node.width / 2
        val halfH = node.height / 2
        if (x >= node.x - halfW && x <= node.x + halfW &&
            y >= node.y - halfH && y <= node.y + halfH) {
            return node.id
        }
    }
    return null
}

private fun findCollapseButtonAt(mindMap: MindMap, x: Float, y: Float): String? {
    val radius = 30f // approx hit radius
    for (node in mindMap.nodes.values) {
        if (node.children.isEmpty()) continue
        if (!isVisible(mindMap, node.id)) continue

        val btnX = node.x + node.width/2 + 10f // Scale handled by caller passing canvas coords?
        // Wait, caller passes canvasX/Y which are unscaled?
        // No, caller logic: val canvasX = (down.x - offset.x) / scale. So we compare with node.x (unscaled).
        // Hit radius should be unscaled too?
        // Drawn radius is 15f * scale. So in model coords it is 15f.

        val btnY = node.y
        val dist = hypot(x - btnX, y - btnY)
        if (dist <= 30f) { // slightly larger hit area
            return node.id
        }
    }
    return null
}

private fun findCrossLinkAt(mindMap: MindMap, x: Float, y: Float): String? {
    val threshold = 20f
    for (link in mindMap.crossLinks) {
        val start = mindMap.nodes[link.startNodeId] ?: continue
        val end = mindMap.nodes[link.endNodeId] ?: continue
        if (!isVisible(mindMap, start.id) || !isVisible(mindMap, end.id)) continue

        val dist = distanceToSegment(x, y, start.x, start.y, end.x, end.y)
        if (dist <= threshold) {
            return link.id
        }
    }
    return null
}

private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    if (l2 == 0f) return hypot(px - x1, py - y1)

    var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
    t = t.coerceIn(0f, 1f)
    val tx = x1 + t * (x2 - x1)
    val ty = y1 + t * (y2 - y1)
    return hypot(px - tx, py - ty)
}

private fun hypot(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx*dx + dy*dy)
