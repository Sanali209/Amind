package com.mindmap.android.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
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
    highlightedNodeId: String? = null,
    selectedCrossLinkId: String? = null,
    onNodeClick: (String, Offset) -> Unit,
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    onCrossLinkClick: (String, Offset) -> Unit,
    onNodeDrop: (String, String) -> Unit,
    onNodeMoved: ((String, Float, Float) -> Unit)? = null,
    onToggleCollapse: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    LaunchedEffect(highlightedNodeId) {
        if (highlightedNodeId != null) {
            val targetNode = mindMap.nodes[highlightedNodeId]
            if (targetNode != null) {
                // We want the node to be at the center.
                // The canvas translation logic uses: x = (targetX * scale + centerX + offset.x)
                // We want that translated x to equal canvas center (size.width / 2)
                // So, offset.x = - (targetNode.x * scale)
                offset = Offset(-targetNode.x * scale, -targetNode.y * scale)
            }
        }
    }

    var draggingNodeId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoverTargetId by remember { mutableStateOf<String?>(null) }

    // Cache decoded bitmaps
    val bitmapCache = remember { mutableMapOf<String, Bitmap?>() }

    // We key on a composite hash of all image data to detect changes
    // Or we rely on the fact that if an image is added, the 'images' list content changes
    val imagesHash = remember(mindMap) {
         mindMap.nodes.values.sumOf { it.images.hashCode() }
    }

    LaunchedEffect(mindMap, imagesHash) {
        // Simple cleanup of removed nodes
        val currentIds = mindMap.nodes.keys
        val cachedIds = bitmapCache.keys.toList()
        cachedIds.forEach { if (!currentIds.contains(it)) bitmapCache.remove(it) }

        mindMap.nodes.values.forEach { node ->
            if (node.images.isNotEmpty()) {
                if (!bitmapCache.containsKey(node.id)) {
                    try {
                        val bytes = Base64.decode(node.images[0], Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        bitmapCache[node.id] = bmp
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                bitmapCache.remove(node.id)
            }
        }
    }

    val textPaint = remember {
        TextPaint().apply {
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

    val checkboxPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
    }

    val checkMarkPaint = remember {
        Paint().apply {
             color = android.graphics.Color.BLACK
             style = Paint.Style.STROKE
             strokeWidth = 4f
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

                        val collapseHitId = findCollapseButtonAt(mindMap, canvasX, canvasY)
                        if (collapseHitId != null) {
                            down.consume()
                            onToggleCollapse(collapseHitId)
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
                            } else if (moved && draggingNodeId != null && hoverTargetId == null && mindMap.layoutType == "FREE") {
                                onNodeMoved?.invoke(draggingNodeId!!, dragOffset.x / scale, dragOffset.y / scale)
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
                                 if (linkId != null) onCrossLinkClick(linkId, down.position)
                                 else onBackgroundTap()
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
                this, root, mindMap, centerX, centerY, scale, draggingNodeId, dragOffset, hoverTargetId, selectedNodeId, highlightedNodeId,
                textPaint, tagPaint, notePaint, collapsePaint, checkboxPaint, checkMarkPaint, bitmapCache
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

                val isSelected = (link.id == selectedCrossLinkId)
                val linkColor = if (isSelected) Color.Yellow else Color.Red
                val linkStrokeWidth = if (isSelected) 6f * scale else 3f * scale

                drawLine(linkColor, startPoint, endPoint, strokeWidth = linkStrokeWidth)
                val angle = atan2(endPoint.y - startPoint.y, endPoint.x - startPoint.x)
                val arrowSize = 20f * scale

                rotate(degrees = Math.toDegrees(angle.toDouble()).toFloat(), pivot = endPoint) {
                    val path = Path().apply {
                        moveTo(endPoint.x, endPoint.y)
                        lineTo(endPoint.x - arrowSize, endPoint.y - arrowSize / 2)
                        lineTo(endPoint.x - arrowSize, endPoint.y + arrowSize / 2)
                        close()
                    }
                    drawPath(path, linkColor)
                }

                if (!link.label.isNullOrBlank() || !link.note.isNullOrBlank()) {
                     val mx = (startPoint.x + endPoint.x) / 2
                     val my = (startPoint.y + endPoint.y) / 2

                     val labelText = (link.label ?: "") + (if (!link.note.isNullOrBlank()) " 📝" else "")

                     drawIntoCanvas { canvas ->
                        textPaint.textSize = 30f * scale
                        textPaint.color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.RED
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.nativeCanvas.drawText(labelText, mx, my - 10f * scale, textPaint)
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
    highlightedNodeId: String?,
    textPaint: TextPaint,
    tagPaint: Paint,
    notePaint: Paint,
    collapsePaint: Paint,
    checkboxPaint: Paint,
    checkMarkPaint: Paint,
    bitmapCache: Map<String, Bitmap?>
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
            val color = if (node.colorOverride != null) Color(node.colorOverride!!.toInt())
                        else if (node.id == mindMap.rootNodeId) Color.Gray
                        else RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]
            val path = Path().apply { moveTo(nx, ny); cubicTo(nx, ny + (cy - ny) / 2, cx, cy - (cy - ny) / 2, cx, cy) }
            scope.drawPath(path = path, color = color, style = Stroke(width = 5f * scale))
            drawNodeTree(scope, child, mindMap, centerX, centerY, scale, draggingNodeId, dragOffset, hoverTargetId, selectedNodeId, highlightedNodeId, textPaint, tagPaint, notePaint, collapsePaint, checkboxPaint, checkMarkPaint, bitmapCache)
        }
    }

    val nWidth = node.width * scale
    val nHeight = node.height * scale
    val left = nx - nWidth / 2
    val top = ny - nHeight / 2

    val nodeColor = if (node.colorOverride != null) Color(node.colorOverride!!.toInt())
                    else if (node.id == mindMap.rootNodeId) Color.White
                    else RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]

    scope.drawRoundRect(
        color = nodeColor,
        topLeft = Offset(left, top),
        size = Size(nWidth, nHeight),
        cornerRadius = CornerRadius(16f * scale, 16f * scale),
    )

    scope.drawRoundRect(
        color = Color.DarkGray,
        topLeft = Offset(left, top),
        size = Size(nWidth, nHeight),
        cornerRadius = CornerRadius(16f * scale, 16f * scale),
        style = Stroke(width = 3f * scale)
    )

    if (node.id == selectedNodeId) {
        scope.drawRoundRect(
            color = Color.Cyan,
            topLeft = Offset(left, top),
            size = Size(nWidth, nHeight),
            cornerRadius = CornerRadius(16f * scale, 16f * scale),
            style = Stroke(width = 6f * scale)
        )
    } else if (highlightedNodeId != null && node.id == highlightedNodeId) {
        scope.drawRoundRect(
            color = Color.Yellow,
            topLeft = Offset(left, top),
            size = Size(nWidth, nHeight),
            cornerRadius = CornerRadius(16f * scale, 16f * scale),
            style = Stroke(width = 8f * scale)
        )
    } else if (highlightedNodeId != null) {
        // Dim the non-highlighted nodes
        scope.drawRoundRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(left, top),
            size = Size(nWidth, nHeight),
            cornerRadius = CornerRadius(16f * scale, 16f * scale)
        )
    }

    scope.drawIntoCanvas { canvas ->
        // Prepare TextPaint for StaticLayout
        textPaint.textSize = 40f * scale
        textPaint.color = android.graphics.Color.BLACK
        // StaticLayout expects Paint.Align.LEFT to calculate widths correctly relative to origin.
        // It handles the actual text alignment (Center) internally via the Alignment parameter.
        textPaint.textAlign = Paint.Align.LEFT

        // Draw Images from Cache
        var currentContentY = ny - nHeight/2 + 20f*scale

        val cachedBitmap = bitmapCache[node.id]
        if (cachedBitmap != null) {
            val imgW = 100f * scale
            val imgH = 100f * scale
            // Centered image
            val dst = android.graphics.RectF(nx - imgW/2, currentContentY, nx + imgW/2, currentContentY + imgH)
            canvas.nativeCanvas.drawBitmap(cachedBitmap, null, dst, null)
            currentContentY += imgH + 10f*scale
        }

        // Draw Checkbox
        if (node.isTodo) {
            val cbSize = 30f * scale
            // Draw checkbox to left of text? Or centered?
            // "Nodes need a maximum width and text word wrap"
            // If wrapping text, maybe left aligned layout is better?
            // Layout assumed centered. Let's keep checkbox centered or to left of wrapping text.
            // Simplified: centered checkbox above text if TODO.
            val cbX = nx - cbSize/2
            val cbY = currentContentY

            val cbRect = android.graphics.RectF(cbX, cbY, cbX + cbSize, cbY + cbSize)
            checkboxPaint.strokeWidth = 3f * scale
            checkboxPaint.color = android.graphics.Color.BLACK

            canvas.nativeCanvas.drawRect(cbRect, checkboxPaint)

            if (node.isChecked) {
                val p = android.graphics.Path().apply {
                    moveTo(cbX + 5f*scale, cbY + cbSize/2)
                    lineTo(cbX + cbSize/2, cbY + cbSize - 5f*scale)
                    lineTo(cbX + cbSize - 5f*scale, cbY + 5f*scale)
                }

                checkMarkPaint.strokeWidth = 4f * scale
                checkMarkPaint.color = android.graphics.Color.BLACK

                canvas.nativeCanvas.drawPath(p, checkMarkPaint)
            }
            currentContentY += cbSize + 10f*scale
        }

        // Draw Text (Wrapped)
        val maxTextWidth = 400f * scale // Scaled max width
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(
                node.text, 0, node.text.length, textPaint, maxTextWidth.toInt()
            )
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                node.text, textPaint, maxTextWidth.toInt(),
                android.text.Layout.Alignment.ALIGN_CENTER, 1f, 0f, false
            )
        }

        canvas.nativeCanvas.save()
        // Center the StaticLayout horizontally
        val textX = nx - staticLayout.width / 2
        val textY = currentContentY
        canvas.nativeCanvas.translate(textX, textY)
        staticLayout.draw(canvas.nativeCanvas)
        canvas.nativeCanvas.restore()

        currentContentY += staticLayout.height + 10f*scale

        if (!node.note.isNullOrBlank()) {
             notePaint.textSize = 25f * scale
             canvas.nativeCanvas.drawText("📝", nx + nWidth/2 - 20f*scale, ny - nHeight/2 + 30f*scale, notePaint)
        }

        if (node.tags.isNotEmpty()) {
            tagPaint.textSize = 30f * scale
            tagPaint.color = android.graphics.Color.DKGRAY
            // Centered tags below text
            val tagsString = node.tags.joinToString(", ") { "#$it" }
            // Measure to center
            val bounds = android.graphics.Rect()
            tagPaint.getTextBounds(tagsString, 0, tagsString.length, bounds)
            val tagX = nx - bounds.width()/2
            val tagY = currentContentY + 30f*scale // approximate ascent
            canvas.nativeCanvas.drawText(tagsString, tagX, tagY, tagPaint)
        }

        if (node.children.isNotEmpty()) {
            val indicator = if (node.isCollapsed) "+" else "-"
            // Increase button size
            val btnRadius = 24f * scale
            val btnX = nx + nWidth/2 + 10f*scale + btnRadius/2 // Adjust position slightly if needed, but relative to edge is fine.
            val btnY = ny

            val paintCircle = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; style = android.graphics.Paint.Style.FILL }
            val paintStroke = android.graphics.Paint().apply { color = android.graphics.Color.CYAN; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f * scale }
            canvas.nativeCanvas.drawCircle(btnX, btnY, btnRadius, paintCircle)
            canvas.nativeCanvas.drawCircle(btnX, btnY, btnRadius, paintStroke)
            collapsePaint.textSize = 35f * scale // Larger text
            val bounds = android.graphics.Rect(); collapsePaint.getTextBounds(indicator, 0, indicator.length, bounds)
            val ty = btnY + bounds.height()/2f
            canvas.nativeCanvas.drawText(indicator, btnX, ty, collapsePaint)
        }
    }
}

// Helpers
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
    val hw = w / 2; val hh = h / 2
    val tX = if (dx != 0f) { val dir = if (dx > 0) 1 else -1; (dir * hw) / dx } else Float.MAX_VALUE
    val tY = if (dy != 0f) { val dir = if (dy > 0) 1 else -1; (dir * hh) / dy } else Float.MAX_VALUE
    val t = min(abs(tX), abs(tY))
    return Offset(cx + dx * t, cy + dy * t)
}

private fun findNodeAt(mindMap: MindMap, x: Float, y: Float): String? {
    for (node in mindMap.nodes.values) {
        if (!isVisible(mindMap, node.id)) continue
        if (x >= node.x - node.width/2 && x <= node.x + node.width/2 &&
            y >= node.y - node.height/2 && y <= node.y + node.height/2) return node.id
    }
    return null
}

private fun findCollapseButtonAt(mindMap: MindMap, x: Float, y: Float): String? {
    for (node in mindMap.nodes.values) {
        if (node.children.isEmpty() || !isVisible(mindMap, node.id)) continue
        // Match drawing logic: btnX = node.x + node.width/2 + 10 + radius/2 ~ but simpler approx is fine
        // Previous radius was 15, hit 30. New radius 24. Hit 48.
        // Drawing: btnX = nx + nWidth/2 + 10f*scale + btnRadius/2 (approx 12)
        // Let's approximate btnX relative to unscaled coordinates (Canvas x/y passed here are unscaled?)
        // Wait, input x/y are divided by scale in MindMapCanvas.
        val btnRadius = 24f
        val btnX = node.x + node.width/2 + 10f + btnRadius/2
        val dist = hypot(x - btnX, y - node.y)
        if (dist <= 50f) return node.id // Increased hit area
    }
    return null
}

private fun findCrossLinkAt(mindMap: MindMap, x: Float, y: Float): String? {
    val threshold = 20f
    for (link in mindMap.crossLinks) {
        val start = mindMap.nodes[link.startNodeId] ?: continue
        val end = mindMap.nodes[link.endNodeId] ?: continue
        if (!isVisible(mindMap, start.id) || !isVisible(mindMap, end.id)) continue
        if (distanceToSegment(x, y, start.x, start.y, end.x, end.y) <= threshold) return link.id
    }
    return null
}

private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    if (l2 == 0f) return hypot(px - x1, py - y1)
    var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
    t = t.coerceIn(0f, 1f)
    return hypot(px - (x1 + t * (x2 - x1)), py - (y1 + t * (y2 - y1)))
}

private fun hypot(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx*dx + dy*dy)
