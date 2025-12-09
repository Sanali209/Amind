package com.mindmap.android.ui.editor

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mindmap.android.model.CrossLink
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import com.mindmap.android.utils.FileHelper
import com.mindmap.android.utils.MindMapLayout
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    mindMapId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mindMap by remember { mutableStateOf<MindMap?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) } // To force redraw

    // UI States
    var showShareMenu by remember { mutableStateOf(false) }

    // Crosslink State
    var isSelectingCrosslinkTarget by remember { mutableStateOf(false) }
    var crosslinkSourceId by remember { mutableStateOf<String?>(null) }

    // Crosslink selection/edit
    var selectedCrossLinkId by remember { mutableStateOf<String?>(null) }
    var showCrossLinkEditDialog by remember { mutableStateOf(false) }
    var editCrossLinkLabel by remember { mutableStateOf("") }
    var crossLinkMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var showCrossLinkMenu by remember { mutableStateOf(false) }

    // Dialog States for Node
    var showEditDialog by remember { mutableStateOf(false) }
    var editingNodeId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf<Long?>(null) }

    // Context Menu State
    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    // Paints for measuring (created once here to pass to layout)
    val textPaint = remember {
        Paint().apply {
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val tagPaint = remember {
        Paint().apply {
            textSize = 30f
        }
    }

    // Export Launcher (Save As)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
        onResult = { uri ->
            uri?.let {
                mindMap?.let { map ->
                    val md = FileHelper.exportToMarkdown(map)
                    try {
                        context.contentResolver.openOutputStream(it)?.use { output ->
                            output.write(md.toByteArray())
                        }
                        Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    )

    LaunchedEffect(mindMapId) {
        mindMap = FileHelper.loadMindMap(context, mindMapId)
        if (mindMap == null) {
            Toast.makeText(context, "Error loading map", Toast.LENGTH_SHORT).show()
            onBack()
        } else {
             MindMapLayout.layout(mindMap!!, textPaint, tagPaint)
        }
    }

    fun layoutAndSave() {
        mindMap?.let {
            MindMapLayout.layout(it, textPaint, tagPaint)
            FileHelper.saveMindMap(context, it)
            refreshTrigger++
        }
    }

    // Helper to delete node
    fun deleteNode(nodeId: String) {
        mindMap?.let { map ->
            deleteNodeRecursive(map, nodeId)
            layoutAndSave()
        }
    }

    // Helper to delete crosslink
    fun deleteCrossLink(linkId: String) {
        mindMap?.let { map ->
            map.crossLinks.removeAll { it.id == linkId }
            layoutAndSave()
        }
    }

    // Helper to share MD file
    fun shareMarkdown() {
        mindMap?.let { map ->
            try {
                val md = FileHelper.exportToMarkdown(map)
                val fileName = "${map.title.replace(" ", "_")}.md"
                val file = File(context.cacheDir, fileName)
                file.writeText(md)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_SUBJECT, map.title)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Fallback for apps that don't handle text/markdown
                // Maybe better to use text/plain for maximum compatibility with chat apps?
                // Request says "md file". Some apps treat text/plain as body text, not file.
                // Let's stick to text/markdown or text/plain.
                // Telegram supports files. Whatsapp supports files.
                // Setting type to "text/plain" often puts content in body.
                // Setting to "*/*" forces file often.
                // Let's try text/plain but with stream.

                context.startActivity(Intent.createChooser(intent, "Share Mind Map"))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (mindMap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mindMap!!.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save as Markdown...") },
                                onClick = {
                                    showShareMenu = false
                                    exportLauncher.launch("${mindMap!!.title}.md")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share via App...") },
                                onClick = {
                                    showShareMenu = false
                                    shareMarkdown()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212)) // Canvas background
        ) {
            MindMapCanvas(
                mindMap = mindMap!!,
                modifier = Modifier.fillMaxSize(),
                onNodeClick = { nodeId ->
                    if (isSelectingCrosslinkTarget && crosslinkSourceId != null) {
                        // Create Crosslink
                        if (nodeId != crosslinkSourceId) {
                            mindMap!!.crossLinks.add(CrossLink(startNodeId = crosslinkSourceId!!, endNodeId = nodeId))
                            layoutAndSave()
                            Toast.makeText(context, "Link Created", Toast.LENGTH_SHORT).show()
                        }
                        isSelectingCrosslinkTarget = false
                        crosslinkSourceId = null
                    } else {
                        // Clear previous selection
                        selectedCrossLinkId = null
                        showCrossLinkMenu = false
                    }
                },
                onNodeLongClick = { nodeId, offset ->
                    menuNodeId = nodeId
                    menuOffset = offset
                    showMenu = true
                    selectedCrossLinkId = null
                    showCrossLinkMenu = false
                },
                onBackgroundTap = {
                    isSelectingCrosslinkTarget = false
                    crosslinkSourceId = null
                    showMenu = false
                    selectedCrossLinkId = null
                    showCrossLinkMenu = false
                },
                onCrossLinkClick = { linkId, offset ->
                    selectedCrossLinkId = linkId
                    crossLinkMenuOffset = offset
                    showCrossLinkMenu = true
                    showMenu = false
                }
            )

            // Selection overlay/instruction
            if (isSelectingCrosslinkTarget) {
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                    Card {
                        Text("Tap target node for crosslink", modifier = Modifier.padding(8.dp))
                    }
                }
            }

            // Node Context Menu
            if (showMenu && menuNodeId != null) {
                val density = LocalContext.current.resources.displayMetrics.density
                val dpOffset = DpOffset((menuOffset.x / density).dp, (menuOffset.y / density).dp)

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    offset = dpOffset
                ) {
                    DropdownMenuItem(
                        text = { Text("Add Child") },
                        onClick = {
                            val parent = mindMap!!.nodes[menuNodeId]
                            if (parent != null) {
                                val newNode = MindMapNode(text = "New Node", parentId = parent.id)
                                mindMap!!.nodes[newNode.id] = newNode
                                parent.children.add(newNode.id)
                                layoutAndSave()
                            }
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            val node = mindMap!!.nodes[menuNodeId]
                            if (node != null) {
                                editingNodeId = node.id
                                editText = node.text
                                editNote = node.note ?: ""
                                editTags = node.tags.joinToString(", ")
                                editColor = node.colorOverride
                                showEditDialog = true
                            }
                            showMenu = false
                        }
                    )
                     DropdownMenuItem(
                        text = { Text("Add Crosslink") },
                        onClick = {
                            crosslinkSourceId = menuNodeId
                            isSelectingCrosslinkTarget = true
                            Toast.makeText(context, "Select target node", Toast.LENGTH_SHORT).show()
                            showMenu = false
                        }
                    )
                    if (menuNodeId != mindMap!!.rootNodeId) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                deleteNode(menuNodeId!!)
                                showMenu = false
                            },
                             colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }

            // CrossLink Context Menu
             if (showCrossLinkMenu && selectedCrossLinkId != null) {
                val density = LocalContext.current.resources.displayMetrics.density
                val dpOffset = DpOffset((crossLinkMenuOffset.x / density).dp, (crossLinkMenuOffset.y / density).dp)

                 DropdownMenu(
                     expanded = showCrossLinkMenu,
                     onDismissRequest = { showCrossLinkMenu = false },
                     offset = dpOffset
                 ) {
                     DropdownMenuItem(
                         text = { Text("Edit Label") },
                         leadingIcon = { Icon(Icons.Default.Edit, "Edit") },
                         onClick = {
                             val link = mindMap!!.crossLinks.find { it.id == selectedCrossLinkId }
                             if (link != null) {
                                 editCrossLinkLabel = link.label ?: ""
                                 showCrossLinkEditDialog = true
                             }
                             showCrossLinkMenu = false
                         }
                     )
                     DropdownMenuItem(
                         text = { Text("Delete") },
                         leadingIcon = { Icon(Icons.Default.Delete, "Delete") },
                         onClick = {
                             deleteCrossLink(selectedCrossLinkId!!)
                             showCrossLinkMenu = false
                             selectedCrossLinkId = null
                         },
                         colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                     )
                 }
             }

            // Node Edit Dialog
            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    title = { Text("Edit Node") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                label = { Text("Text") }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editNote,
                                onValueChange = { editNote = it },
                                label = { Text("Note") },
                                minLines = 3
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editTags,
                                onValueChange = { editTags = it },
                                label = { Text("Tags (comma separated)") }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Simple Color Picker (just a few presets for now)
                            Text("Color Override:")
                            Row {
                                val colors = listOf(null, Color.Red.value.toLong(), Color.Blue.value.toLong(), Color.Green.value.toLong(), 0xFFFFA500) // Default(null), Red, Blue, Green, Orange
                                colors.forEach { c ->
                                    val bg = if (c != null) Color(c.toULong()) else Color.Gray
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .padding(4.dp)
                                            .background(bg)
                                            .clickable { editColor = c }
                                    )
                                }
                            }
                            if (editColor != null) {
                                Text("Selected custom color")
                            } else {
                                Text("Using Theme Color")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val node = mindMap!!.nodes[editingNodeId]
                            if (node != null) {
                                node.text = editText
                                node.note = editNote.ifBlank { null }

                                // Parse tags
                                node.tags.clear()
                                if (editTags.isNotBlank()) {
                                    node.tags.addAll(editTags.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                                }

                                node.colorOverride = editColor

                                // If root changed, update map title?
                                if (node.id == mindMap!!.rootNodeId) {
                                    mindMap!!.title = editText
                                }
                                layoutAndSave()
                            }
                            showEditDialog = false
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // CrossLink Edit Dialog
            if (showCrossLinkEditDialog) {
                AlertDialog(
                    onDismissRequest = { showCrossLinkEditDialog = false },
                    title = { Text("Edit Link Label") },
                    text = {
                        OutlinedTextField(
                            value = editCrossLinkLabel,
                            onValueChange = { editCrossLinkLabel = it },
                            label = { Text("Label") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val link = mindMap!!.crossLinks.find { it.id == selectedCrossLinkId }
                            if (link != null) {
                                link.label = editCrossLinkLabel.ifBlank { null }
                                layoutAndSave()
                            }
                            showCrossLinkEditDialog = false
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCrossLinkEditDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

fun deleteNodeRecursive(mindMap: MindMap, nodeId: String) {
    val node = mindMap.nodes[nodeId] ?: return

    // Remove from parent's children list
    node.parentId?.let { parentId ->
        mindMap.nodes[parentId]?.children?.remove(nodeId)
    }

    // Recursively delete children
    // Copy list to avoid concurrent modification
    val children = node.children.toList()
    children.forEach { childId ->
        deleteNodeRecursive(mindMap, childId)
    }

    // Remove crosslinks involving this node
    mindMap.crossLinks.removeAll { it.startNodeId == nodeId || it.endNodeId == nodeId }

    // Remove node itself
    mindMap.nodes.remove(nodeId)
}
