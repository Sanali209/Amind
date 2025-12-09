package com.mindmap.android.ui.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import com.mindmap.android.model.CrossLink
import com.mindmap.android.utils.FileHelper
import com.mindmap.android.utils.MindMapLayout
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

    // Crosslink State
    var isSelectingCrosslinkTarget by remember { mutableStateOf(false) }
    var crosslinkSourceId by remember { mutableStateOf<String?>(null) }

    // Dialog States
    var showEditDialog by remember { mutableStateOf(false) }
    var editingNodeId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }

    // Context Menu State
    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mindMapId) {
        mindMap = FileHelper.loadMindMap(context, mindMapId)
        if (mindMap == null) {
            // Should not happen, but handle it
            Toast.makeText(context, "Error loading map", Toast.LENGTH_SHORT).show()
            onBack()
        } else {
             MindMapLayout.layout(mindMap!!)
        }
    }

    // Save on changes
    fun save() {
        mindMap?.let {
            FileHelper.saveMindMap(context, it)
            refreshTrigger++ // Force recompose
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
                    IconButton(onClick = {
                        val md = FileHelper.exportToMarkdown(mindMap!!)
                        val filename = "${mindMap!!.title}.md"
                        val file = java.io.File(context.getExternalFilesDir(null), filename)
                        file.writeText(md)
                        Toast.makeText(context, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
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
                            save()
                            Toast.makeText(context, "Link Created", Toast.LENGTH_SHORT).show()
                        }
                        isSelectingCrosslinkTarget = false
                        crosslinkSourceId = null
                    } else {
                        // Normal click (maybe select? for now do nothing or show toast)
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

            // Context Menu
            if (showMenu && menuNodeId != null) {
                // Determine dropdown position. We need density to convert px to dp.
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
                                MindMapLayout.layout(mindMap!!)
                                save()
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
                                deleteNodeRecursive(mindMap!!, menuNodeId!!)
                                MindMapLayout.layout(mindMap!!)
                                save()
                                showMenu = false
                            },
                             colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }

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
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val node = mindMap!!.nodes[editingNodeId]
                            if (node != null) {
                                node.text = editText
                                node.note = editNote.ifBlank { null }
                                // If root changed, update map title?
                                if (node.id == mindMap!!.rootNodeId) {
                                    mindMap!!.title = editText
                                }
                                save()
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
