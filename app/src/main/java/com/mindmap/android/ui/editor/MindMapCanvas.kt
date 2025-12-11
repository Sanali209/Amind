package com.mindmap.android.ui.editor

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
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
import androidx.compose.ui.graphics.asImageBitmap
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
    onNodeClick: (String, Offset) -> Unit,
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    onCrossLinkClick: (String, Offset) -> Unit,
    onNodeDrop: (String, String) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onToggleCheckbox: (String) -> Unit, // New callback
    modifier: Modifier = Modifier
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    var draggingNodeId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoverTargetId by remember { mutableStateOf<String?>(null) }

    // Bitmap cache?
    // In Compose Canvas, decoding bitmaps every frame is bad.
    // Ideally we should have a ViewModel or cache holding these.
    // For now, simple remember based cache might be okay if node list is stable?
    // Or just simple cache map.
    val bitmapCache = remember { mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.LEFT // Changed to left for easier layout with checkbox
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
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        val canvasX = (down.position.x - size.width / 2 - offset.x) / scale
                        val canvasY = (down.position.y - size.height / 2 - offset.y) / scale

                        // Check collapse button
                        val collapseHitId = findCollapseButtonAt(mindMap, canvasX, canvasY)
                        if (collapseHitId != null) {
                            down.consume()
                            onToggleCollapse(collapseHitId)
                            return@awaitPointerEventScope
                        }

                        // Check checkbox
                        val checkboxHitId = findCheckboxAt(mindMap, canvasX, canvasY)
                        if (checkboxHitId != null) {
                            down.consume()
                            onToggleCheckbox(checkboxHitId)
                            return@awaitPointerEventScope
                        }

                        val hitNodeId = findNodeAt(mindMap, canvasX, canvasY)

                        if (hitNodeId != null) {
                            var moved = false
                            drag(down.id) { change ->
                                val count = currentEvent.changes.size
                                if (count == 1) {
                                    change.consume()
                                    draggingNodeId = hitNodeId
                                    moved = true
                                    dragOffset += change.positionChange()

                                    val currX = (change.position.x - size.width / 2 - offset.x) / scale
                                    val currY = (change.position.y - size.height / 2 - offset.y) / scale

                                    val target = findNodeAt(mindMap, currX, currY)
                                    hoverTargetId = if (target != hitNodeId) target else null

                                } else {
                                    draggingNodeId = null
                                    hoverTargetId = null

                                    val zoomChange = currentEvent.calculateZoom()
                                    val panChange = currentEvent.calculatePan()

                                    scale *= zoomChange
                                    offset += panChange
                                    change.consume()
                                }
                            }

                            if (draggingNodeId != null && hoverTargetId != null) {
                                onNodeDrop(draggingNodeId!!, hoverTargetId!!)
                            } else if (!moved) {
                                onNodeClick(hitNodeId, down.position)
                            }

                            draggingNodeId = null
                            dragOffset = Offset.Zero
                            hoverTargetId = null

                        } else {
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
                collapsePaint,
                bitmapCache
            )
        }

        // Draw Crosslinks
        mindMap.crossLinks.forEach { link ->
            val start = mindMap.nodes[link.startNodeId]
            val end = mindMap.nodes[link.endNodeId]

            if (start != null && end != null && isVisible(mindMap, start.id) && isVisible(mindMap, end.id)) {
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
                    strokeWidth = 3f * scale,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
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

                // Label
                if (!link.label.isNullOrBlank()) {
                     val mx = (startPoint.x + endPoint.x) / 2
                     val my = (startPoint.y + endPoint.y) / 2

                     drawIntoCanvas { canvas ->
                        textPaint.textSize = 30f * scale
                        textPaint.color = android.graphics.Color.RED
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.nativeCanvas.drawText(link.label!!, mx, my, textPaint)
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
    collapsePaint: Paint,
    bitmapCache: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap>
) {
    val isDragging = (node.id == draggingNodeId)
    val dx = if (isDragging) dragOffset.x else 0f
    val dy = if (isDragging) dragOffset.y else 0f

    val nx = node.x * scale + centerX + dx
    val ny = node.y * scale + centerY + dy

    if (!node.isCollapsed) {
        node.children.forEach { childId ->
            val child = mindMap.nodes[childId] ?: return@forEach
            val cx = child.x * scale + centerX
            val cy = child.y * scale + centerY

            val color = if (node.colorOverride != null) Color(node.colorOverride!!.toULong())
                        else if (node.id == mindMap.rootNodeId) Color.Gray
                        else RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]

            val path = Path().apply {
                moveTo(nx, ny)
                // Different curve for tree?
                // Just use same cubic for now, works fine.
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

            drawNodeTree(scope, child, mindMap, centerX, centerY, scale, draggingNodeId, dragOffset, hoverTargetId, selectedNodeId, textPaint, tagPaint, notePaint, collapsePaint, bitmapCache)
        }
    }

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

    scope.drawRoundRect(
        color = Color.DarkGray,
        topLeft = Offset(left, top),
        size = Size(nWidth, nHeight),
        cornerRadius = CornerRadius(16f * scale, 16f * scale)
    )

    // Draw Content

    var currentY = top + 20f * scale
    val paddingX = 10f * scale

    // Images
    if (node.images.isNotEmpty()) {
        val b64 = node.images[0]
        // Cache check
        // Key by hash of b64 or just ID? Image might change so better to not cache by ID alone if images mutable.
        // Assuming images append only or replace. Let's use simple check.
        // Or hash the b64.
        val cacheKey = "${node.id}_${b64.hashCode()}"
        var bmp = bitmapCache[cacheKey]
        if (bmp == null) {
            try {
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                val androidBmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                if (androidBmp != null) {
                    bmp = androidBmp.asImageBitmap()
                    bitmapCache[cacheKey] = bmp
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (bmp != null) {
            // Scale to fit width
            val aspect = bmp.width.toFloat() / bmp.height.toFloat()
            val targetW = nWidth - paddingX * 2
            val targetH = targetW / aspect
            // Clamp height?
            val drawH = if (targetH > 100f*scale) 100f*scale else targetH
            val drawW = drawH * aspect // Keep aspect ratio

            scope.drawImage(
                image = bmp,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (left + (nWidth - drawW)/2).toInt(),
                    currentY.toInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt())
            )
            currentY += drawH + 10f*scale
        }
    }

    val textX: Float
    // Checkbox
    if (node.isTodo) {
        val cbSize = 30f * scale
        val cbX = left + paddingX
        val cbY = currentY

        scope.drawRect(
            color = Color.Black,
            topLeft = Offset(cbX, cbY),
            size = Size(cbSize, cbSize),
            style = Stroke(width = 2f * scale)
        )
        if (node.isChecked) {
             scope.drawRect(
                color = Color.Green,
                topLeft = Offset(cbX + 4f*scale, cbY + 4f*scale),
                size = Size(cbSize - 8f*scale, cbSize - 8f*scale)
            )
        }
        textX = cbX + cbSize + 10f * scale
        // Align text vertically with checkbox
    } else {
        textX = left + paddingX
    }

    scope.drawIntoCanvas { canvas ->
        textPaint.textSize = 40f * scale
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT

        // Vertically center text in line?
        val fm = textPaint.fontMetrics
        val h = fm.descent - fm.ascent

        canvas.nativeCanvas.drawText(node.text, textX, currentY - fm.ascent, textPaint)

        currentY += h + 10f * scale

        if (!node.note.isNullOrBlank()) {
             notePaint.textSize = 25f * scale
             // Draw icon top right
             canvas.nativeCanvas.drawText("📝", nx + nWidth/2 - 20f*scale, ny - nHeight/2 + 30f*scale, notePaint)
        }

        if (node.tags.isNotEmpty()) {
            tagPaint.textSize = 30f * scale
            val tagsString = node.tags.joinToString(", ") { "#$it" }
            canvas.nativeCanvas.drawText(tagsString, left + paddingX, currentY - tagPaint.fontMetrics.ascent, tagPaint)
        }

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
            val bounds = android.graphics.Rect()
            collapsePaint.getTextBounds(indicator, 0, indicator.length, bounds)
            val ty = btnY + bounds.height()/2f

            canvas.nativeCanvas.drawText(indicator, btnX, ty, collapsePaint)
        }
    }
}

fun isVisible(mindMap: MindMap, nodeId: String): Boolean {
    var currentId = nodeId
    while(currentId != mindMap.rootNodeId) {
        val node = mindMap.nodes[currentId] ?: return false
        val parentId = node.parentId ?: return (currentId == mindMap.rootNodeId)
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

private fun findNodeAt(mindMap: MindMap, x: Float, y: Float): String? {
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
    for (node in mindMap.nodes.values) {
        if (node.children.isEmpty()) continue
        if (!isVisible(mindMap, node.id)) continue

        val btnX = node.x + node.width/2 + 10f
        val btnY = node.y
        val dist = hypot(x - btnX, y - btnY)
        if (dist <= 30f) {
            return node.id
        }
    }
    return null
}

private fun findCheckboxAt(mindMap: MindMap, x: Float, y: Float): String? {
     for (node in mindMap.nodes.values) {
        if (!node.isTodo) continue
        if (!isVisible(mindMap, node.id)) continue

        // Checkbox pos: left + padding, top + 20 + images height
        val paddingX = 10f
        val left = node.x - node.width/2
        val top = node.y - node.height/2

        var imgH = 0f
        if (node.images.isNotEmpty()) imgH = 120f // approx logic from layout

        val cbX = left + paddingX
        val cbY = top + 20f + imgH // approx
        val cbSize = 30f

        if (x >= cbX && x <= cbX + cbSize && y >= cbY && y <= cbY + cbSize) {
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
