package com.mindmap.android.model

import java.util.UUID

data class MindMapNode(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "New Node",
    var note: String? = null,
    val children: MutableList<String> = mutableListOf(),
    var parentId: String? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 100f,
    var height: Float = 100f,
    var color: Long = 0xFF000000, // Default Color, will be overridden by layout/theme
    var colorOverride: Long? = null,
    val tags: MutableList<String> = mutableListOf()
)

data class CrossLink(
    val id: String = UUID.randomUUID().toString(),
    val startNodeId: String,
    val endNodeId: String,
    var label: String? = null
)

data class MindMap(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Mind Map",
    var rootNodeId: String = "",
    val nodes: MutableMap<String, MindMapNode> = mutableMapOf(),
    val crossLinks: MutableList<CrossLink> = mutableListOf(),
    var lastModified: Long = System.currentTimeMillis()
) {
    // Helper to create a default map
    companion object {
        fun createDefault(): MindMap {
            val root = MindMapNode(text = "Central Idea")
            return MindMap(
                rootNodeId = root.id,
                nodes = mutableMapOf(root.id to root)
            )
        }
    }
}
