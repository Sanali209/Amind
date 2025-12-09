package com.mindmap.android.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object FileHelper {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun saveMindMap(context: Context, mindMap: MindMap) {
        mindMap.lastModified = System.currentTimeMillis()
        val filename = "${mindMap.id}.json"
        val file = File(context.filesDir, filename)
        FileWriter(file).use { writer ->
            gson.toJson(mindMap, writer)
        }
    }

    fun loadMindMap(context: Context, id: String): MindMap? {
        val filename = "$id.json"
        val file = File(context.filesDir, filename)
        if (!file.exists()) return null
        return FileReader(file).use { reader ->
            gson.fromJson(reader, MindMap::class.java)
        }
    }

    fun listMindMaps(context: Context): List<MindMap> {
        val files = context.filesDir.listFiles { _, name -> name.endsWith(".json") }
        return files?.mapNotNull { file ->
            try {
                FileReader(file).use { reader ->
                    gson.fromJson(reader, MindMap::class.java)
                }
            } catch (e: Exception) {
                null
            }
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    fun deleteMindMap(context: Context, id: String) {
        val filename = "$id.json"
        val file = File(context.filesDir, filename)
        if (file.exists()) {
            file.delete()
        }
    }

    fun exportToMarkdown(mindMap: MindMap): String {
        val sb = StringBuilder()
        sb.append("# ${mindMap.title}\n\n")

        val root = mindMap.nodes[mindMap.rootNodeId]
        if (root != null) {
            traverseNodeForMarkdown(root, mindMap, 0, sb)
        }

        return sb.toString()
    }

    private fun traverseNodeForMarkdown(
        node: MindMapNode,
        mindMap: MindMap,
        level: Int,
        sb: StringBuilder
    ) {
        // Indentation
        repeat(level) { sb.append("  ") }
        sb.append("- ${node.text}\n")

        // Notes as blockquote or just text under
        if (!node.note.isNullOrBlank()) {
            repeat(level + 1) { sb.append("  ") }
            sb.append("> ${node.note}\n")
        }

        for (childId in node.children) {
            val child = mindMap.nodes[childId]
            if (child != null) {
                traverseNodeForMarkdown(child, mindMap, level + 1, sb)
            }
        }
    }
}
