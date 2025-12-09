package com.mindmap.android.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import com.mindmap.android.ui.theme.RainbowColors
import kotlin.math.abs

@Composable
fun MindMapCanvas(
    mindMap: MindMap,
    onNodeClick: (String) -> Unit,
    onNodeLongClick: (String, Offset) -> Unit,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Transformations
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableStateOf(1f) }

    // Center the root initially (Hack: we assume 0,0 is center of layout, so we offset by screen center)
    // We can do this better by measuring size, but for now lets start centered.
    // Actually, detectTransformGestures accumulates change, so initial offset can be screen center.

    val context = LocalContext.current
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE // Default, will change per node
            textSize = 40f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
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
                        // Convert tapOffset to Canvas Coordinates
                        val canvasX = (tapOffset.x - size.width / 2 - offset.x) / scale
                        val canvasY = (tapOffset.y - size.height / 2 - offset.y) / scale

                        val clickedNodeId = findNodeAt(mindMap, canvasX, canvasY)
                        if (clickedNodeId != null) {
                            onNodeClick(clickedNodeId)
                        } else {
                            onBackgroundTap()
                        }
                    },
                    onLongPress = { tapOffset ->
                        val canvasX = (tapOffset.x - size.width / 2 - offset.x) / scale
                        val canvasY = (tapOffset.y - size.height / 2 - offset.y) / scale

                        val clickedNodeId = findNodeAt(mindMap, canvasX, canvasY)
                        if (clickedNodeId != null) {
                            onNodeLongClick(clickedNodeId, tapOffset) // Pass screen offset for menu
                        }
                    }
                )
            }
    ) {
        // Apply transformations
        // We want (0,0) of our layout to be at the center of the screen + offset
        val centerX = size.width / 2 + offset.x
        val centerY = size.height / 2 + offset.y

        // Draw connections first (so they are behind nodes)
        // 1. Parent-Child connections
        drawConnections(this, mindMap, centerX, centerY, scale)

        // 2. Crosslinks
        mindMap.crossLinks.forEach { link ->
            val start = mindMap.nodes[link.startNodeId]
            val end = mindMap.nodes[link.endNodeId]
            if (start != null && end != null) {
                val sx = start.x * scale + centerX
                val sy = start.y * scale + centerY
                val ex = end.x * scale + centerX
                val ey = end.y * scale + centerY

                drawLine(
                    color = Color.Gray,
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = 3f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)
                )
            }
        }

        // Draw Nodes
        mindMap.nodes.values.forEach { node ->
            val nx = node.x * scale + centerX
            val ny = node.y * scale + centerY

            // Determine Color (Cycle through rainbow based on hash or index)
            // If root, special color?
            val color = if (node.id == mindMap.rootNodeId) Color.White else RainbowColors[abs(node.id.hashCode()) % RainbowColors.size]

            // Draw Node Shape (Oval)
            drawCircle(
                color = color,
                radius = 50f * scale, // Dynamic size based on text? Fixed for now.
                center = Offset(nx, ny),
                style = Stroke(width = 5f * scale)
            )
            // Fill
            drawCircle(
                color = Color.DarkGray, // Fill background
                radius = 50f * scale,
                center = Offset(nx, ny)
            )

            // Draw Text
            drawIntoCanvas { canvas ->
                textPaint.textSize = 40f * scale
                textPaint.color = android.graphics.Color.WHITE // Always white text on dark bg
                canvas.nativeCanvas.drawText(
                    node.text,
                    nx,
                    ny + (15f * scale), // approximate vertical center alignment
                    textPaint
                )

                // Draw Note Indicator if exists
                if (!node.note.isNullOrBlank()) {
                     notePaint.textSize = 25f * scale
                     canvas.nativeCanvas.drawText(
                         "📝",
                         nx,
                         ny + (45f * scale),
                         notePaint
                     )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawConnections(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    mindMap: MindMap,
    centerX: Float,
    centerY: Float,
    scale: Float
) {
    // Simple traversal to draw lines
    // We iterate all nodes and draw line to children
    mindMap.nodes.values.forEach { parent ->
        val px = parent.x * scale + centerX
        val py = parent.y * scale + centerY

        // Color for this branch
        val color = if (parent.id == mindMap.rootNodeId) Color.Gray else RainbowColors[abs(parent.id.hashCode()) % RainbowColors.size]

        parent.children.forEach { childId ->
            val child = mindMap.nodes[childId] ?: return@forEach
            val cx = child.x * scale + centerX
            val cy = child.y * scale + centerY

            // Bezier Curve
            val path = Path().apply {
                moveTo(px, py)
                // Control points for smooth curve
                cubicTo(
                    px, py + (cy - py) / 2, // cp1
                    cx, cy - (cy - py) / 2, // cp2
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
    // Reverse iterate to find top-most (though Z-order is arbitrary here)
    // Hit test radius = 50f (same as draw radius)
    val radius = 60f // slightly larger hit area

    for (node in mindMap.nodes.values) {
        val dx = x - node.x
        val dy = y - node.y
        if (dx * dx + dy * dy <= radius * radius) {
            return node.id
        }
    }
    return null
}
