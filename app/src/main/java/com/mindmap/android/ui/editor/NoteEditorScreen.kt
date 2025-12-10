package com.mindmap.android.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    initialNote: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var noteContent by remember { mutableStateOf(initialNote) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Note (Markdown)") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(noteContent) }) {
                        Icon(Icons.Default.Done, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Toolbar (Mockup)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Button(onClick = { noteContent += "**Bold**" }) { Text("B") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { noteContent += "*Italic*" }) { Text("I") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { noteContent += "# Header" }) { Text("H1") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { noteContent += "- List" }) { Text("List") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { noteContent += "[Link](url)" }) { Text("Link") }
            }
            Spacer(Modifier.height(8.dp))

            // Editor Area
            BasicTextField(
                value = noteContent,
                onValueChange = { noteContent = it },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray, MaterialTheme.shapes.small)
                    .padding(16.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White)
            )
        }
    }
}
