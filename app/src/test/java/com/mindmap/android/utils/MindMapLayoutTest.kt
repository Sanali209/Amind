package com.mindmap.android.utils

import com.mindmap.android.model.MindMap
import com.mindmap.android.model.MindMapNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MindMapLayoutTest {

    @Test
    fun testLayoutCoordinates() {
        val root = MindMapNode(text = "Root", id = "root")
        val child1 = MindMapNode(text = "Child 1", id = "c1", parentId = "root")
        val child2 = MindMapNode(text = "Child 2", id = "c2", parentId = "root")

        root.children.add("c1")
        root.children.add("c2")

        val map = MindMap(
            rootNodeId = "root",
            nodes = mutableMapOf(
                "root" to root,
                "c1" to child1,
                "c2" to child2
            )
        )

        MindMapLayout.layout(map)

        // Root should be 0,0
        assertEquals(0f, root.x, 0.01f)
        assertEquals(0f, root.y, 0.01f)

        // Children should be at distance 300 (LEVEL_DISTANCE)
        val dist1 = Math.sqrt((child1.x * child1.x + child1.y * child1.y).toDouble())
        val dist2 = Math.sqrt((child2.x * child2.x + child2.y * child2.y).toDouble())

        assertEquals(300.0, dist1, 0.1)
        assertEquals(300.0, dist2, 0.1)

        // Children should not be at same position
        assertNotEquals(child1.x, child2.x, 0.1f)
        assertNotEquals(child1.y, child2.y, 0.1f)
    }
}
