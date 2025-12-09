package com.mindmap.android

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
        setContent {
            MindMapTheme {
                MindMapApp()
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
                    MindMapLayout.layout(newMap)
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
