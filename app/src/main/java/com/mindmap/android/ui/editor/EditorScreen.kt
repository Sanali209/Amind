package com.mindmap.android.ui.editor

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import com.google.gson.Gson
import com.mindmap.android.model.CrossLink
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import com.mindmap.android.utils.FileHelper
import com.mindmap.android.utils.MindMapLayout
import java.io.File
import java.io.InputStream
import java.util.Stack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    mindMapId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mindMap by remember { mutableStateOf<MindMap?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Undo/Redo
    val undoStack = remember { Stack<String>() }
    val redoStack = remember { Stack<String>() }
    val gson = remember { Gson() }

    // UI States
    var showShareMenu by remember { mutableStateOf(false) }
    var isSelectingCrosslinkTarget by remember { mutableStateOf(false) }
    var crosslinkSourceId by remember { mutableStateOf<String?>(null) }

    // Selection / Menus
    var selectedCrossLinkId by remember { mutableStateOf<String?>(null) }
    var showCrossLinkEditDialog by remember { mutableStateOf(false) }
    var editCrossLinkLabel by remember { mutableStateOf("") }
    var crossLinkMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var showCrossLinkMenu by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    // Dialogs / Editors
    var showEditDialog by remember { mutableStateOf(false) }
    var editingNodeId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }

    var showNoteEditor by remember { mutableStateOf(false) }
    var noteEditingId by remember { mutableStateOf<String?>(null) } // Node ID or CrossLink ID
    var initialNoteContent by remember { mutableStateOf("") }
    var isEditingCrossLinkNote by remember { mutableStateOf(false) }

    var showColorPicker by remember { mutableStateOf(false) }
    var colorPickerNodeId by remember { mutableStateOf<String?>(null) }

    // Paints
    val textPaint = remember { Paint().apply { textSize = 40f; typeface = Typeface.DEFAULT_BOLD } }
    val tagPaint = remember { Paint().apply { textSize = 30f } }

    // Image Picker
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                val nodeId = menuNodeId
                if (nodeId != null && mindMap != null) {
                     try {
                         val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                         val bytes = inputStream?.readBytes()
                         if (bytes != null) {
                             val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                             val node = mindMap!!.nodes[nodeId]
                             if (node != null) {
                                 // Push history
                                 undoStack.push(gson.toJson(mindMap!!))
                                 redoStack.clear()

                                 node.images.clear()
                                 node.images.add(base64)

                                 MindMapLayout.layout(mindMap!!, textPaint, tagPaint)
                                 FileHelper.saveMindMap(context, mindMap!!)
                                 refreshTrigger++
                             }
                         }
                         inputStream?.close()
                     } catch (e: Exception) {
                         Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                     }
                }
            }
        }
    )

    // Export Launcher (Save As Markdown)
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
            onBack()
        } else {
             MindMapLayout.layout(mindMap!!, textPaint, tagPaint)
        }
    }

    fun pushHistory() {
        mindMap?.let {
            undoStack.push(gson.toJson(it))
            redoStack.clear()
        }
    }

    fun saveMap() {
        mindMap?.let {
            MindMapLayout.layout(it, textPaint, tagPaint)
            FileHelper.saveMindMap(context, it)
            refreshTrigger++
        }
    }

    // Helper functions (Restored)
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

        val children = node.children.toList()
        children.forEach { childId ->
            deleteNodeRecursive(mindMap, childId)
        }

        mindMap.crossLinks.removeAll { it.startNodeId == nodeId || it.endNodeId == nodeId }
        mindMap.nodes.remove(nodeId)
    }

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

                context.startActivity(Intent.createChooser(intent, "Share Mind Map (Markdown)"))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareNative() {
        mindMap?.let { map ->
            try {
                val json = com.google.gson.Gson().toJson(map)
                val fileName = "${map.title.replace(" ", "_")}.json"
                val file = File(context.cacheDir, fileName)
                file.writeText(json)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, map.title)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(intent, "Share Mind Map (JSON)"))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Sub-Screens ---

    if (showNoteEditor) {
        BackHandler {
             showNoteEditor = false
        }
        NoteEditorScreen(
            initialNote = initialNoteContent,
            onSave = { newNote ->
                if (noteEditingId != null && mindMap != null) {
                    pushHistory()
                    if (isEditingCrossLinkNote) {
                        val link = mindMap!!.crossLinks.find { it.id == noteEditingId }
                        link?.note = newNote
                    } else {
                        val node = mindMap!!.nodes[noteEditingId]
                        node?.note = newNote
                    }
                    saveMap()
                }
                showNoteEditor = false
            },
            onCancel = { showNoteEditor = false }
        )
        return // Early return to show note editor
    }

    // --- Main Screen ---

    if (mindMap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (undoStack.isNotEmpty()) {
                            mindMap?.let { redoStack.push(gson.toJson(it)) }
                            val json = undoStack.pop()
                            mindMap = gson.fromJson(json, MindMap::class.java)
                            saveMap()
                        }
                    }, enabled = !undoStack.isEmpty()) {
                        Text("Undo", color = if(!undoStack.isEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                    IconButton(onClick = {
                         if (redoStack.isNotEmpty()) {
                            mindMap?.let { undoStack.push(gson.toJson(it)) }
                            val json = redoStack.pop()
                            mindMap = gson.fromJson(json, MindMap::class.java)
                            saveMap()
                        }
                    }, enabled = !redoStack.isEmpty()) {
                        Text("Redo", color = if(!redoStack.isEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save as Markdown...") },
                                onClick = { showShareMenu = false; exportLauncher.launch("${mindMap!!.title}.md") }
                            )
                            DropdownMenuItem(
                                text = { Text("Share via App (Markdown)...") },
                                onClick = {
                                    showShareMenu = false
                                    shareMarkdown()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share via App (JSON)...") },
                                onClick = {
                                    showShareMenu = false
                                    shareNative()
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
                .background(Color(0xFF121212))
        ) {
            MindMapCanvas(
                mindMap = mindMap!!,
                selectedNodeId = menuNodeId,
                modifier = Modifier.fillMaxSize(),
                onNodeClick = { nodeId, offset ->
                    if (isSelectingCrosslinkTarget && crosslinkSourceId != null) {
                        if (nodeId != crosslinkSourceId) {
                            pushHistory()
                            mindMap!!.crossLinks.add(CrossLink(startNodeId = crosslinkSourceId!!, endNodeId = nodeId))
                            saveMap()
                            Toast.makeText(context, "Link Created", Toast.LENGTH_SHORT).show()
                        }
                        isSelectingCrosslinkTarget = false
                        crosslinkSourceId = null
                    } else {
                        menuNodeId = nodeId
                        menuOffset = offset
                        showMenu = true
                        selectedCrossLinkId = null
                        showCrossLinkMenu = false
                    }
                },
                onNodeLongClick = { nodeId, offset ->
                    menuNodeId = nodeId
                    menuOffset = offset
                    showMenu = true
                },
                onBackgroundTap = {
                    isSelectingCrosslinkTarget = false
                    crosslinkSourceId = null
                    showMenu = false
                    selectedCrossLinkId = null
                    showCrossLinkMenu = false
                    menuNodeId = null
                },
                onCrossLinkClick = { linkId, offset ->
                    selectedCrossLinkId = linkId
                    crossLinkMenuOffset = offset
                    showCrossLinkMenu = true
                    showMenu = false
                },
                onNodeDrop = { draggedId, targetId ->
                     val draggedNode = mindMap!!.nodes[draggedId]
                     val targetNode = mindMap!!.nodes[targetId]
                     if (draggedNode != null && targetNode != null) {
                         if (!isDescendant(mindMap!!, draggedId, targetId)) {
                             pushHistory()
                             draggedNode.parentId?.let { mindMap!!.nodes[it]?.children?.remove(draggedId) }
                             draggedNode.parentId = targetId
                             targetNode.children.add(draggedId)
                             saveMap()
                         } else {
                             Toast.makeText(context, "Cannot move node to its descendant", Toast.LENGTH_SHORT).show()
                         }
                     }
                },
                onToggleCollapse = { nodeId ->
                    val node = mindMap!!.nodes[nodeId]
                    if (node != null) {
                        pushHistory()
                        node.isCollapsed = !node.isCollapsed
                        saveMap()
                    }
                }
            )

            if (isSelectingCrosslinkTarget) {
                 Box(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                    Card { Text("Tap target node for crosslink", modifier = Modifier.padding(8.dp)) }
                }
            }

            // Node Context Menu
            if (showMenu && menuNodeId != null) {
                val density = LocalContext.current.resources.displayMetrics.density
                val dpOffset = DpOffset((menuOffset.x / density).dp, (menuOffset.y / density).dp)
                val node = mindMap!!.nodes[menuNodeId]!!

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, offset = dpOffset) {
                    DropdownMenuItem(text = { Text("Add Child") }, onClick = {
                        pushHistory()
                        val newNode = MindMapNode(text = "New Node", parentId = node.id)
                        mindMap!!.nodes[newNode.id] = newNode
                        node.children.add(newNode.id)
                        saveMap()
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Edit Text/Tags") }, onClick = {
                        editingNodeId = node.id
                        editText = node.text
                        editTags = node.tags.joinToString(", ")
                        showEditDialog = true
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Edit Note") }, onClick = {
                        noteEditingId = node.id
                        initialNoteContent = node.note ?: ""
                        isEditingCrossLinkNote = false
                        showNoteEditor = true
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Toggle Todo") }, onClick = {
                        pushHistory()
                        if (!node.isTodo) {
                            node.isTodo = true
                            node.isChecked = false
                        } else {
                            node.isChecked = !node.isChecked
                        }
                        saveMap()
                        showMenu = false
                    })
                     if (node.isTodo) {
                        DropdownMenuItem(text = { Text("Remove Todo") }, onClick = {
                            pushHistory()
                            node.isTodo = false
                            saveMap()
                            showMenu = false
                        })
                    }
                    DropdownMenuItem(text = { Text("Change Color") }, onClick = {
                        colorPickerNodeId = node.id
                        showColorPicker = true
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Attach Image") }, onClick = {
                        imageLauncher.launch("image/*")
                        showMenu = false
                    })
                    DropdownMenuItem(text = { Text("Add Crosslink") }, onClick = {
                        crosslinkSourceId = menuNodeId
                        isSelectingCrosslinkTarget = true
                        showMenu = false
                    })
                    if (node.id != mindMap!!.rootNodeId) {
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            pushHistory()
                            val n = mindMap!!.nodes[menuNodeId]
                            if (n != null && n.parentId != null) {
                                mindMap!!.nodes[n.parentId]?.children?.remove(n.id)
                            }
                            deleteNodeRecursive(mindMap!!, menuNodeId!!)
                            saveMap()
                            showMenu = false
                        }, colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error))
                    }
                }
            }

            // CrossLink Menu
            if (showCrossLinkMenu && selectedCrossLinkId != null) {
                 val density = LocalContext.current.resources.displayMetrics.density
                val dpOffset = DpOffset((crossLinkMenuOffset.x / density).dp, (crossLinkMenuOffset.y / density).dp)
                val link = mindMap!!.crossLinks.find { it.id == selectedCrossLinkId }

                DropdownMenu(expanded = showCrossLinkMenu, onDismissRequest = { showCrossLinkMenu = false }, offset = dpOffset) {
                    DropdownMenuItem(text = { Text("Edit Label") }, onClick = {
                        editCrossLinkLabel = link?.label ?: ""
                        showCrossLinkEditDialog = true
                        showCrossLinkMenu = false
                    })
                    DropdownMenuItem(text = { Text("Edit Note") }, onClick = {
                         noteEditingId = selectedCrossLinkId
                         initialNoteContent = link?.note ?: ""
                         isEditingCrossLinkNote = true
                         showNoteEditor = true
                         showCrossLinkMenu = false
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        pushHistory()
                        mindMap!!.crossLinks.removeIf { it.id == selectedCrossLinkId }
                        saveMap()
                        showCrossLinkMenu = false
                    }, colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error))
                }
            }

            // Dialogs
            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    title = { Text("Edit Node") },
                    text = {
                        Column {
                            OutlinedTextField(value = editText, onValueChange = { editText = it }, label = { Text("Text") })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = editTags, onValueChange = { editTags = it }, label = { Text("Tags") })
                        }
                    },
                    confirmButton = {
                         TextButton(onClick = {
                            val node = mindMap!!.nodes[editingNodeId]
                            if (node != null) {
                                pushHistory()
                                node.text = editText
                                node.tags.clear()
                                if (editTags.isNotBlank()) node.tags.addAll(editTags.split(",").map{it.trim()}.filter{it.isNotEmpty()})

                                // Auto-rename map if root node
                                if (node.id == mindMap!!.rootNodeId) {
                                    mindMap!!.title = editText
                                }

                                saveMap()
                            }
                            showEditDialog = false
                         }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } }
                )
            }

            if (showColorPicker) {
                 AlertDialog(
                    onDismissRequest = { showColorPicker = false },
                    title = { Text("Select Color") },
                    text = {
                        // Simple Color Grid
                        Column {
                            val colors = listOf(
                                0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF42A5F5, 0xFF26A69A, 0xFF66BB6A, 0xFFFFCA28, 0xFFFFA726, 0xFF8D6E63, 0xFFBDBDBD
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                colors.take(5).forEach { c ->
                                     Box(Modifier.size(40.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape).clickable {
                                         val node = mindMap!!.nodes[colorPickerNodeId]
                                         if (node != null) {
                                             pushHistory()
                                             node.colorOverride = c
                                             saveMap()
                                         }
                                         showColorPicker = false
                                     })
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                colors.takeLast(5).forEach { c ->
                                     Box(Modifier.size(40.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape).clickable {
                                         val node = mindMap!!.nodes[colorPickerNodeId]
                                         if (node != null) {
                                             pushHistory()
                                             node.colorOverride = c
                                             saveMap()
                                         }
                                         showColorPicker = false
                                     })
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                val node = mindMap!!.nodes[colorPickerNodeId]
                                if (node != null) {
                                    pushHistory()
                                    node.colorOverride = null // Reset
                                    saveMap()
                                }
                                showColorPicker = false
                            }) { Text("Reset to Default") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showColorPicker = false }) { Text("Cancel") } }
                )
            }
        }
    }
}
