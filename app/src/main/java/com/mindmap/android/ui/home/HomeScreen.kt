package com.mindmap.android.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindmap.android.model.MindMap
import com.mindmap.android.utils.FileHelper
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMap: (String) -> Unit,
    onCreateMap: () -> Unit
) {
    val context = LocalContext.current
    var mindMaps by remember { mutableStateOf(emptyList<MindMap>()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        mindMaps = FileHelper.listMindMaps(context)
    }

    // Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                    val jsonString = inputStream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader -> reader.readText() }

                    if (jsonString != null) {
                         val importedMap = com.google.gson.Gson().fromJson(jsonString, MindMap::class.java)

                         // Validate or check for existing
                         val existing = FileHelper.loadMindMap(context, importedMap.id)
                         if (existing != null) {
                             if (importedMap.lastModified > existing.lastModified) {
                                 FileHelper.saveMindMap(context, importedMap) // Overwrite if newer
                                 Toast.makeText(context, "Imported newer version", Toast.LENGTH_SHORT).show()
                             } else {
                                 Toast.makeText(context, "Existing map is newer or same", Toast.LENGTH_SHORT).show()
                                 // Maybe prompt? For now, we follow spec "if newer replace".
                                 // But if older, maybe we should ignore or save as copy?
                                 // Let's just ignore if older for now as per "replace if newer" implication.
                             }
                         } else {
                             FileHelper.saveMindMap(context, importedMap)
                             Toast.makeText(context, "Imported successfully", Toast.LENGTH_SHORT).show()
                         }
                         refreshTrigger++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Import failed: Invalid JSON", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Mind Maps") },
                actions = {
                    TextButton(onClick = { importLauncher.launch("application/json") }) {
                        Text("Import JSON")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                onCreateMap()
            }) {
                Icon(Icons.Default.Add, contentDescription = "Create New Mind Map")
            }
        }
    ) { padding ->
        if (mindMaps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No mind maps yet. Create one!", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mindMaps) { map ->
                    MindMapCard(
                        mindMap = map,
                        onClick = { onOpenMap(map.id) },
                        onDelete = {
                             FileHelper.deleteMindMap(context, map.id)
                             refreshTrigger++
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MindMapCard(
    mindMap: MindMap,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = mindMap.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(mindMap.lastModified)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
