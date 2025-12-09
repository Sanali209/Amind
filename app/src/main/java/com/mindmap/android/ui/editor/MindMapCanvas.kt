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
    onNodeClick: (String, Offset) -> Unit, // Changed to include Offset
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    onCrossLinkClick: (String, Offset) -> Unit,
    onNodeDrop: (String, String) -> Unit, // draggedId, targetId
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

        // Draw connections
        drawConnections(this, mindMap, centerX, centerY, scale)

        // Draw Nodes
        mindMap.nodes.values.forEach { node ->
            val isDragging = (node.id == draggingNodeId)

            // If dragging, draw at offset position
            val dx = if (isDragging) dragOffset.x else 0f
            val dy = if (isDragging) dragOffset.y else 0f

            val nx = node.x * scale + centerX + dx
            val ny = node.y * scale + centerY + dy

            val nWidth = node.width * scale
            val nHeight = node.height * scale

            val left = nx - nWidth / 2
            val top = ny - nHeight / 2

            // Highlight if target
            val isHoverTarget = (node.id == hoverTargetId)
            val borderColor = if (isHoverTarget) Color.Green else Color.Transparent

            // Determine Color
            val nodeColor = if (node.colorOverride != null) {
                Color(node.colorOverride!!.toULong())
            } else if (node.id == mindMap.rootNodeId) {
                Color.White
            } else {
                RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]
            }

            // Draw Rounded Rectangle
            drawRoundRect(
                color = nodeColor,
                topLeft = Offset(left, top),
                size = Size(nWidth, nHeight),
                cornerRadius = CornerRadius(16f * scale, 16f * scale),
                style = Stroke(width = if (isHoverTarget) 8f * scale else 5f * scale)
            )
             if (isHoverTarget) {
                drawRoundRect(
                    color = Color.Green,
                    topLeft = Offset(left, top),
                    size = Size(nWidth, nHeight),
                    cornerRadius = CornerRadius(16f * scale, 16f * scale),
                    style = Stroke(width = 8f * scale)
                )
             }

            // Fill
            drawRoundRect(
                color = Color.DarkGray,
                topLeft = Offset(left, top),
                size = Size(nWidth, nHeight),
                cornerRadius = CornerRadius(16f * scale, 16f * scale)
            )

            // Draw Text
            drawIntoCanvas { canvas ->
                textPaint.textSize = 40f * scale
                textPaint.color = android.graphics.Color.WHITE
                textPaint.textAlign = Paint.Align.CENTER

                val textYOffset = if (node.tags.isNotEmpty()) -15f * scale else 15f * scale

                canvas.nativeCanvas.drawText(
                    node.text,
                    nx,
                    ny + textYOffset,
                    textPaint
                )

                if (!node.note.isNullOrBlank()) {
                     notePaint.textSize = 25f * scale
                     canvas.nativeCanvas.drawText(
                         "📝",
                         nx + nWidth/2 - 20f*scale,
                         ny - nHeight/2 + 30f*scale,
                         notePaint
                     )
                }

                if (node.tags.isNotEmpty()) {
                    tagPaint.textSize = 30f * scale
                    tagPaint.textAlign = Paint.Align.CENTER

                    val tagsY = ny + 35f * scale
                    val tagsString = node.tags.joinToString(", ") { "#$it" }
                    canvas.nativeCanvas.drawText(tagsString, nx, tagsY, tagPaint)
                }
            }
        }

        // Draw Crosslinks (Red, with Arrow, and Label)
        mindMap.crossLinks.forEach { link ->
            val start = mindMap.nodes[link.startNodeId]
            val end = mindMap.nodes[link.endNodeId]
            if (start != null && end != null) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawConnections(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    mindMap: MindMap,
    centerX: Float,
    centerY: Float,
    scale: Float
) {
    mindMap.nodes.values.forEach { parent ->
        val px = parent.x * scale + centerX
        val py = parent.y * scale + centerY

        val color = if (parent.colorOverride != null) Color(parent.colorOverride!!.toULong())
                    else if (parent.id == mindMap.rootNodeId) Color.Gray
                    else RainbowColors[abs(parent.id.hashCode()) % RainbowColors.size]

        parent.children.forEach { childId ->
            val child = mindMap.nodes[childId] ?: return@forEach
            val cx = child.x * scale + centerX
            val cy = child.y * scale + centerY

            val path = Path().apply {
                moveTo(px, py)
                cubicTo(
                    px, py + (cy - py) / 2,
                    cx, cy - (cy - py) / 2,
                    cx, cy
                )
            }

            scope.drawPath(
                path = path,
                color = color,
                style = Stroke(width = 5f * scale)
            )
        }
    }
}

private fun findNodeAt(mindMap: MindMap, x: Float, y: Float): String? {
    for (node in mindMap.nodes.values) {
        val halfW = node.width / 2
        val halfH = node.height / 2
        if (x >= node.x - halfW && x <= node.x + halfW &&
            y >= node.y - halfH && y <= node.y + halfH) {
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
