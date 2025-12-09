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
import androidx.compose.material.icons.filled.Refresh
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
import com.google.gson.Gson
import com.mindmap.android.model.CrossLink
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import com.mindmap.android.utils.FileHelper
import com.mindmap.android.utils.MindMapLayout
import java.io.File
import java.util.Stack
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

    // Undo/Redo Stacks
    // We store JSON strings as deep copies (simple way to snapshot)
    val undoStack = remember { Stack<String>() }
    val redoStack = remember { Stack<String>() }
    val gson = remember { Gson() }

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

    // Rename Map State
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

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

    // History Management
    fun pushHistory() {
        mindMap?.let {
            val json = gson.toJson(it)
            undoStack.push(json)
            redoStack.clear()
        }
    }

    fun performUndo() {
        if (undoStack.isNotEmpty()) {
            // Save current to redo
            mindMap?.let {
                redoStack.push(gson.toJson(it))
            }
            val json = undoStack.pop()
            mindMap = gson.fromJson(json, MindMap::class.java)
            mindMap?.let { MindMapLayout.layout(it, textPaint, tagPaint) } // Recalc visual sizes
            refreshTrigger++
            FileHelper.saveMindMap(context, mindMap!!)
            Toast.makeText(context, "Undo", Toast.LENGTH_SHORT).show()
        }
    }

    fun performRedo() {
        if (redoStack.isNotEmpty()) {
             mindMap?.let {
                undoStack.push(gson.toJson(it))
            }
            val json = redoStack.pop()
            mindMap = gson.fromJson(json, MindMap::class.java)
            mindMap?.let { MindMapLayout.layout(it, textPaint, tagPaint) }
            refreshTrigger++
            FileHelper.saveMindMap(context, mindMap!!)
            Toast.makeText(context, "Redo", Toast.LENGTH_SHORT).show()
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
        pushHistory()
        mindMap?.let { map ->
            deleteNodeRecursive(map, nodeId)
            layoutAndSave()
        }
    }

    // Helper to delete crosslink
    fun deleteCrossLink(linkId: String) {
        pushHistory()
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
                title = {
                    Row(
                        modifier = Modifier.clickable {
                            renameText = mindMap!!.title
                            showRenameDialog = true
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mindMap!!.title)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo/Redo
                    IconButton(onClick = { performUndo() }, enabled = !undoStack.isEmpty()) {
                        Text("Undo", color = if(!undoStack.isEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                    IconButton(onClick = { performRedo() }, enabled = !redoStack.isEmpty()) {
                        Text("Redo", color = if(!redoStack.isEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename Map") },
                                leadingIcon = { Icon(Icons.Default.Edit, "Rename") },
                                onClick = {
                                    showShareMenu = false
                                    renameText = mindMap!!.title
                                    showRenameDialog = true
                                }
                            )
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
                selectedNodeId = menuNodeId, // Pass selection state
                modifier = Modifier.fillMaxSize(),
                onNodeClick = { nodeId, offset ->
                    // Standard Click now handles Selection OR Menu
                    // If selection mode active:
                    if (isSelectingCrosslinkTarget && crosslinkSourceId != null) {
                        // Create Crosslink
                        if (nodeId != crosslinkSourceId) {
                            pushHistory()
                            mindMap!!.crossLinks.add(CrossLink(startNodeId = crosslinkSourceId!!, endNodeId = nodeId))
                            layoutAndSave()
                            Toast.makeText(context, "Link Created", Toast.LENGTH_SHORT).show()
                        }
                        isSelectingCrosslinkTarget = false
                        crosslinkSourceId = null
                    } else {
                        // Open Context Menu on Tap (as requested fallback for 1-finger drag conflict)
                        menuNodeId = nodeId
                        menuOffset = offset
                        showMenu = true
                        selectedCrossLinkId = null
                        showCrossLinkMenu = false
                    }
                },
                onNodeLongClick = { nodeId, offset ->
                    // Keep this just in case, but primary is now Click
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
                    menuNodeId = null // Clear selection visualization
                },
                onCrossLinkClick = { linkId, offset ->
                    selectedCrossLinkId = linkId
                    crossLinkMenuOffset = offset
                    showCrossLinkMenu = true
                    showMenu = false
                },
                onNodeDrop = { draggedId, targetId ->
                    // Logic for Reparenting
                    val draggedNode = mindMap!!.nodes[draggedId]
                    val targetNode = mindMap!!.nodes[targetId]
                    if (draggedNode != null && targetNode != null) {
                        // Validate: target cannot be descendant of dragged
                        if (!isDescendant(mindMap!!, draggedId, targetId)) {
                            // Record History BEFORE changing
                            pushHistory()

                            // Remove from old parent
                            draggedNode.parentId?.let { pId ->
                                mindMap!!.nodes[pId]?.children?.remove(draggedId)
                            }

                            // Add to new parent
                            draggedNode.parentId = targetId
                            targetNode.children.add(draggedId)

                            layoutAndSave()
                            Toast.makeText(context, "Node Moved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Cannot move to descendant", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onToggleCollapse = { nodeId ->
                    val node = mindMap!!.nodes[nodeId]
                    if (node != null) {
                        pushHistory()
                        node.isCollapsed = !node.isCollapsed
                        layoutAndSave()
                    }
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
                                pushHistory()
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
                                 // Note: Text edit is not structural, user said "no cover typing"
                                 // But technically this saves the map.
                                 // Let's not push history for text edits as per request.
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

            // Rename Dialog
            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text("Rename Mind Map") },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text("Title") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (renameText.isNotBlank()) {
                                mindMap!!.title = renameText
                                layoutAndSave()
                            }
                            showRenameDialog = false
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

// Check if target is a descendant of node (prevent cycles)
fun isDescendant(mindMap: MindMap, nodeId: String, potentialDescendantId: String): Boolean {
    if (nodeId == potentialDescendantId) return true

    val node = mindMap.nodes[nodeId] ?: return false
    for (childId in node.children) {
        if (childId == potentialDescendantId) return true
        if (isDescendant(mindMap, childId, potentialDescendantId)) return true
    }
    return false
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
