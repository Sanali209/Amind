package com.mindmap.android.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mindmap.android.model.CrossLink
import com.mindmap.android.model.MindMap
import com.mindmap.android.ui.theme.RainbowColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

@Composable
fun MindMapCanvas(
    mindMap: MindMap,
    onNodeClick: (String) -> Unit,
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    onCrossLinkClick: (String, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    // Transformations
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE // Default, will change per node
            textSize = 40f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    val tagPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    val notePaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 25f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale *= zoom
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val canvasX = (tapOffset.x - size.width / 2 - offset.x) / scale
                        val canvasY = (tapOffset.y - size.height / 2 - offset.y) / scale

                        val clickedNodeId = findNodeAt(mindMap, canvasX, canvasY)
                        if (clickedNodeId != null) {
                            onNodeClick(clickedNodeId)
                        } else {
                            // Check crosslinks
                            val clickedLinkId = findCrossLinkAt(mindMap, canvasX, canvasY)
                            if (clickedLinkId != null) {
                                onCrossLinkClick(clickedLinkId, tapOffset)
                            } else {
                                onBackgroundTap()
                            }
                        }
                    },
                    onLongPress = { tapOffset ->
                        val canvasX = (tapOffset.x - size.width / 2 - offset.x) / scale
                        val canvasY = (tapOffset.y - size.height / 2 - offset.y) / scale

                        val clickedNodeId = findNodeAt(mindMap, canvasX, canvasY)
                        if (clickedNodeId != null) {
                            onNodeLongClick(clickedNodeId, tapOffset)
                        }
                    }
                )
            }
    ) {
        val centerX = size.width / 2 + offset.x
        val centerY = size.height / 2 + offset.y

        // Draw connections first
        drawConnections(this, mindMap, centerX, centerY, scale)

        // Draw Nodes
        mindMap.nodes.values.forEach { node ->
            val nx = node.x * scale + centerX
            val ny = node.y * scale + centerY
            val nWidth = node.width * scale
            val nHeight = node.height * scale

            val left = nx - nWidth / 2
            val top = ny - nHeight / 2

            // Determine Color
            val nodeColor = if (node.colorOverride != null) {
                Color(node.colorOverride!!)
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
                style = Stroke(width = 5f * scale)
            )
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
                textPaint.textAlign = android.graphics.Paint.Align.CENTER

                // Center text vertically in the top portion (roughly) or center of node if no tags
                // If tags exist, push text up.
                val textYOffset = if (node.tags.isNotEmpty()) -15f * scale else 15f * scale

                canvas.nativeCanvas.drawText(
                    node.text,
                    nx,
                    ny + textYOffset,
                    textPaint
                )

                // Draw Note Indicator
                if (!node.note.isNullOrBlank()) {
                     notePaint.textSize = 25f * scale
                     canvas.nativeCanvas.drawText(
                         "📝",
                         nx + nWidth/2 - 20f*scale,
                         ny - nHeight/2 + 30f*scale, // Top right corner
                         notePaint
                     )
                }

                // Draw Tags
                if (node.tags.isNotEmpty()) {
                    tagPaint.textSize = 30f * scale
                    tagPaint.textAlign = android.graphics.Paint.Align.CENTER

                    val tagsY = ny + 35f * scale
                    val tagsString = node.tags.joinToString(", ") { "#$it" }
                    canvas.nativeCanvas.drawText(tagsString, nx, tagsY, tagPaint)
                }
            }
        }

        // Draw Crosslinks (Red, with Arrow, and Label)
        // Draw AFTER nodes so the arrow is visible on top
        // And use geometry to stop at edge
        mindMap.crossLinks.forEach { link ->
            val start = mindMap.nodes[link.startNodeId]
            val end = mindMap.nodes[link.endNodeId]
            if (start != null && end != null) {
                val sx = start.x * scale + centerX
                val sy = start.y * scale + centerY
                val ex = end.x * scale + centerX
                val ey = end.y * scale + centerY

                // Calculate intersection points with node boundaries
                val startPoint = getRectIntersection(sx, sy, ex, ey, start.width * scale, start.height * scale)
                val endPoint = getRectIntersection(ex, ey, sx, sy, end.width * scale, end.height * scale)

                // Draw Line
                drawLine(
                    color = Color.Red,
                    start = startPoint,
                    end = endPoint,
                    strokeWidth = 3f * scale
                )

                // Draw Arrow at end point
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

                // Draw Label
                if (!link.label.isNullOrBlank()) {
                     val mx = (startPoint.x + endPoint.x) / 2
                     val my = (startPoint.y + endPoint.y) / 2

                     drawIntoCanvas { canvas ->
                        textPaint.textSize = 30f * scale
                        textPaint.color = android.graphics.Color.RED
                        textPaint.textAlign = android.graphics.Paint.Align.CENTER

                        // Draw a small background for text readability?
                        val boundRect = android.graphics.Rect()
                        textPaint.getTextBounds(link.label!!, 0, link.label!!.length, boundRect)
                        val bgPadding = 5f * scale
                        val bgLeft = mx - boundRect.width()/2 - bgPadding
                        val bgTop = my - boundRect.height() - bgPadding - 10f*scale
                        val bgRight = mx + boundRect.width()/2 + bgPadding
                        val bgBottom = my + bgPadding - 10f*scale

                        val bgPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.BLACK
                            alpha = 150
                            style = android.graphics.Paint.Style.FILL
                        }
                        canvas.nativeCanvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, bgPaint)

                        canvas.nativeCanvas.drawText(link.label!!, mx, my - 10f * scale, textPaint)
                     }
                }
            }
        }
    }
}

// Calculate intersection of line (p1 -> p2) with rectangle centered at p1 with size (w, h)
// Actually we want the point on the rect boundary of p1, looking towards p2.
// Wait, the function below calculates intersection on the boundary of the 'center' node (p1)
// towards the 'target' node (p2).
private fun getRectIntersection(cx: Float, cy: Float, tx: Float, ty: Float, w: Float, h: Float): Offset {
    val dx = tx - cx
    val dy = ty - cy
    if (dx == 0f && dy == 0f) return Offset(cx, cy) // Should not happen

    // Slopes
    // We want to find t such that point is on boundary.
    // Boundary x: cx +/- w/2
    // Boundary y: cy +/- h/2

    val hw = w / 2
    val hh = h / 2

    // Check intersection with vertical edges (x = +/- hw)
    // t_x = (+/- hw) / dx
    val tX = if (dx != 0f) {
        val dir = if (dx > 0) 1 else -1
        (dir * hw) / dx
    } else Float.MAX_VALUE

    // Check intersection with horizontal edges (y = +/- hh)
    val tY = if (dy != 0f) {
        val dir = if (dy > 0) 1 else -1
        (dir * hh) / dy
    } else Float.MAX_VALUE

    // Use the smaller t (closest intersection)
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

        val color = if (parent.colorOverride != null) Color(parent.colorOverride!!)
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
    // Check nodes (Rectangular hit test)
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
    // Distance from point to line segment
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

// Helper for distance from point (px, py) to line segment (x1, y1) -> (x2, y2)
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
