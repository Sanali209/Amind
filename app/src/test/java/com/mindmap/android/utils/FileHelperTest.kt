package com.mindmap.android.utils

import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import org.junit.Assert.*
import org.junit.Test

class FileHelperTest {

    @Test
    fun testExportToMarkdown() {
        val root = MindMapNode(text = "Root", id = "root")
        val child1 = MindMapNode(text = "Child 1", id = "c1", parentId = "root", note = "A note")
        val child2 = MindMapNode(text = "Child 2", id = "c2", parentId = "root")
        val subChild = MindMapNode(text = "Sub", id = "sc1", parentId = "c1")

        root.children.add("c1")
        root.children.add("c2")
        child1.children.add("sc1")

        val map = MindMap(
            title = "Test Map",
            rootNodeId = "root",
            nodes = mutableMapOf(
                "root" to root,
                "c1" to child1,
                "c2" to child2,
                "sc1" to subChild
            )
        )

        val md = FileHelper.exportToMarkdown(map)

        assertTrue(md.contains("# Test Map"))
        assertTrue(md.contains("- Root"))
        assertTrue(md.contains("  - Child 1"))
        assertTrue(md.contains("    > A note"))
        assertTrue(md.contains("    - Sub"))
        assertTrue(md.contains("  - Child 2"))
    }
}
