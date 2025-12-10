package com.mindmap.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mindmap.android.model.MindMap
import com.mindmap.android.ui.editor.EditorScreen
import com.mindmap.android.ui.home.HomeScreen
import com.mindmap.android.ui.theme.MindMapTheme
import com.mindmap.android.utils.FileHelper
import com.mindmap.android.utils.MindMapLayout

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MindMapTheme {
                MindMapApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_VIEW || intent.action == android.content.Intent.ACTION_SEND) {
            val uri: android.net.Uri? = intent.data ?: intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
            uri?.let {
                try {
                    val inputStream = contentResolver.openInputStream(it)
                    val json = inputStream?.bufferedReader().use { reader -> reader?.readText() }
                    if (json != null) {
                         val importedMap = com.google.gson.Gson().fromJson(json, MindMap::class.java)

                         val existingMap = FileHelper.loadMindMap(this, importedMap.id)
                         if (existingMap != null) {
                             if (importedMap.lastModified > existingMap.lastModified) {
                                 FileHelper.saveMindMap(this, importedMap)
                                 android.widget.Toast.makeText(this, "Updated Mind Map: ${importedMap.title}", android.widget.Toast.LENGTH_SHORT).show()
                             } else {
                                 android.widget.Toast.makeText(this, "Existing map is newer or same. Skipped.", android.widget.Toast.LENGTH_SHORT).show()
                             }
                         } else {
                             FileHelper.saveMindMap(this, importedMap)
                             android.widget.Toast.makeText(this, "Imported Mind Map: ${importedMap.title}", android.widget.Toast.LENGTH_SHORT).show()
                         }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Failed to import: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
fun MindMapApp() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenMap = { mapId ->
                    navController.navigate("editor/$mapId")
                },
                onCreateMap = {
                    val newMap = MindMap.createDefault()
                    // Initial layout
                    // Need paints for measurement
                    val textPaint = Paint().apply {
                         textSize = 40f
                         typeface = Typeface.DEFAULT_BOLD
                    }
                    val tagPaint = Paint().apply {
                         textSize = 30f
                    }
                    MindMapLayout.layout(newMap, textPaint, tagPaint)
                    FileHelper.saveMindMap(context, newMap)
                    navController.navigate("editor/${newMap.id}")
                }
            )
        }
        composable(
            "editor/{mapId}",
            arguments = listOf(navArgument("mapId") { type = NavType.StringType })
        ) { backStackEntry ->
            val mapId = backStackEntry.arguments?.getString("mapId") ?: return@composable
            EditorScreen(
                mindMapId = mapId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
