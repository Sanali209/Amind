import math
from PySide6.QtWidgets import (QGraphicsView, QGraphicsScene, QGraphicsItem,
                               QGraphicsTextItem, QGraphicsRectItem, QGraphicsPathItem,
                               QMenu, QGraphicsSceneMouseEvent)
from PySide6.QtCore import Qt, QRectF, QPointF, Signal
from PySide6.QtGui import QPen, QBrush, QColor, QPainterPath, QFont, QPainter

from model import MindMap, MindMapNode
from utils import MindMapLayout

class NodeItem(QGraphicsRectItem):
    def __init__(self, node: MindMapNode, view):
        super().__init__()
        self.node = node
        self.view = view

        self.setRect(0, 0, node.width, node.height)
        self.setPos(node.x - node.width / 2, node.y - node.height / 2)

        # Style
        self.setBrush(QBrush(QColor(0xFF, 0xFF, 0xFF))) # White background for now, dark theme later
        if node.color_override:
             # Convert int color to QColor
             c = node.color_override
             # Android int color is ARGB usually, but let's assume standard hex handling
             # 0xFFRRGGBB
             # Python struct or masking
             alpha = (c >> 24) & 0xFF
             red = (c >> 16) & 0xFF
             green = (c >> 8) & 0xFF
             blue = c & 0xFF
             self.setPen(QPen(QColor(red, green, blue, alpha), 2))
        else:
             self.setPen(QPen(Qt.black, 2))

        # Flags
        self.setFlag(QGraphicsItem.ItemIsSelectable)
        self.setFlag(QGraphicsItem.ItemIsMovable)
        self.setFlag(QGraphicsItem.ItemSendsGeometryChanges)

        # Text
        self.text_item = QGraphicsTextItem(node.text, self)
        self.text_item.setPos(10, 10) # Padding

        # Collapse Indicator (if has children)
        if node.children:
            self.collapse_indicator = QGraphicsRectItem(node.width - 20, node.height / 2 - 5, 10, 10, self)
            if node.is_collapsed:
                self.collapse_indicator.setBrush(Qt.black)
            else:
                self.collapse_indicator.setBrush(Qt.white)
            self.collapse_indicator.setPen(QPen(Qt.black))

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            # Check for collapse click
            if hasattr(self, 'collapse_indicator') and self.collapse_indicator.isUnderMouse():
                self.view.toggle_collapse(self.node)
                event.accept()
                return
        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event):
        super().mouseReleaseEvent(event)
        if self.flags() & QGraphicsItem.ItemIsMovable:
             # Check for collision with other nodes for reparenting
             colliding_items = self.collidingItems()
             for item in colliding_items:
                 if isinstance(item, NodeItem) and item != self:
                     # Potential new parent
                     self.view.reparent_node(self.node, item.node)
                     break
             # Even if no reparent, we trigger refresh which resets layout
             self.view.refresh_scene()

    def itemChange(self, change, value):
        if change == QGraphicsItem.ItemPositionHasChanged:
            # Update model
            # Note: This might fight with layout engine if we re-layout constantly
            pass
        return super().itemChange(change, value)

    def contextMenuEvent(self, event):
        menu = QMenu()
        edit_action = menu.addAction("Edit Text")
        add_child_action = menu.addAction("Add Child")
        delete_action = menu.addAction("Delete")

        action = menu.exec(event.screenPos())

        if action == edit_action:
            self.view.edit_node_text(self.node)
        elif action == add_child_action:
            self.view.add_child_node(self.node)
        elif action == delete_action:
            self.view.delete_node(self.node)

class EdgeItem(QGraphicsPathItem):
    def __init__(self, start_pos, end_pos):
        super().__init__()
        path = QPainterPath()
        path.moveTo(start_pos)

        # Bezier curve for smoother look
        ctrl1 = QPointF(start_pos.x() + (end_pos.x() - start_pos.x()) / 2, start_pos.y())
        ctrl2 = QPointF(start_pos.x() + (end_pos.x() - start_pos.x()) / 2, end_pos.y())
        path.cubicTo(ctrl1, ctrl2, end_pos)

        self.setPath(path)
        self.setPen(QPen(Qt.gray, 2))
        self.setZValue(-1) # Behind nodes

class MindMapView(QGraphicsView):
    def __init__(self, main_window):
        super().__init__()
        self.main_window = main_window
        self.scene = QGraphicsScene(self)
        self.setScene(self.scene)
        self.setRenderHint(QPainter.Antialiasing)
        self.setDragMode(QGraphicsView.ScrollHandDrag)

        self.mind_map: MindMap = None

    def set_mind_map(self, mind_map: MindMap):
        self.mind_map = mind_map
        self.refresh_scene()

    def refresh_scene(self):
        if not self.mind_map:
            self.scene.clear()
            return

        # 1. Run Layout
        MindMapLayout.layout(self.mind_map) # Pass font metrics if needed

        # 2. Clear and Draw
        self.scene.clear()

        # Draw Edges first
        self.draw_edges(self.mind_map.nodes[self.mind_map.root_node_id])

        # Draw Nodes
        for node in self.mind_map.nodes.values():
            # Check visibility logic (handled by layout essentially setting positions, but we should only draw visible)
            # Layout sets visible nodes. Collapsed children are not laid out properly or ignored.
            # We need a visibility check helper
            if self.is_node_visible(node):
                item = NodeItem(node, self)
                self.scene.addItem(item)

        # Update Scene Rect
        self.scene.setSceneRect(self.scene.itemsBoundingRect())

    def is_node_visible(self, node: MindMapNode):
        # Walk up to root, if any parent is collapsed, then this is hidden
        curr = node
        while curr.parent_id:
            parent = self.mind_map.nodes.get(curr.parent_id)
            if not parent:
                return True # Orphan?
            if parent.is_collapsed:
                return False
            curr = parent
        return True

    def draw_edges(self, node: MindMapNode):
        if node.is_collapsed:
            return

        start_pos = QPointF(node.x, node.y)

        for child_id in node.children:
            child = self.mind_map.nodes.get(child_id)
            if child:
                end_pos = QPointF(child.x, child.y)
                edge = EdgeItem(start_pos, end_pos)
                self.scene.addItem(edge)
                self.draw_edges(child)

    def toggle_collapse(self, node: MindMapNode):
        node.is_collapsed = not node.is_collapsed
        self.main_window.save_current_map()
        self.refresh_scene()

    def edit_node_text(self, node: MindMapNode):
        from PySide6.QtWidgets import QInputDialog
        text, ok = QInputDialog.getText(self, "Edit Node", "Text:", text=node.text)
        if ok:
            node.text = text
            self.main_window.save_current_map()
            self.refresh_scene()

    def add_child_node(self, parent: MindMapNode):
        new_node = MindMapNode(text="New Child", parent_id=parent.id)
        # Position it near parent initially
        new_node.x = parent.x + 50
        new_node.y = parent.y + 50

        self.mind_map.nodes[new_node.id] = new_node
        parent.children.append(new_node.id)
        parent.is_collapsed = False # Auto expand

        self.main_window.save_current_map()
        self.refresh_scene()

    def delete_node(self, node: MindMapNode):
        if node.id == self.mind_map.root_node_id:
            return # Cannot delete root

        # Remove from parent's children list
        if node.parent_id:
            parent = self.mind_map.nodes.get(node.parent_id)
            if parent and node.id in parent.children:
                parent.children.remove(node.id)

        # Remove node and all descendants
        self.remove_subtree(node)

        self.main_window.save_current_map()
        self.refresh_scene()

    def remove_subtree(self, node: MindMapNode):
        for child_id in list(node.children): # Copy list
            child = self.mind_map.nodes.get(child_id)
            if child:
                self.remove_subtree(child)

        if node.id in self.mind_map.nodes:
            del self.mind_map.nodes[node.id]

    def reparent_node(self, node: MindMapNode, new_parent: MindMapNode):
        # 1. Validation: Prevent cycles (cannot drop parent on its own child/descendant)
        if self.is_descendant(node, new_parent):
            return # Invalid move

        # 2. Remove from old parent
        if node.parent_id:
            old_parent = self.mind_map.nodes.get(node.parent_id)
            if old_parent and node.id in old_parent.children:
                old_parent.children.remove(node.id)

        # 3. Add to new parent
        node.parent_id = new_parent.id
        new_parent.children.append(node.id)
        new_parent.is_collapsed = False

        self.main_window.save_current_map()
        # Scene refresh happens in mouseRelease

    def is_descendant(self, potential_ancestor: MindMapNode, node: MindMapNode):
        # Checks if 'node' is a descendant of 'potential_ancestor'
        # Walk up from 'node' to see if we hit 'potential_ancestor'
        if node.id == potential_ancestor.id:
            return True # It is the same node

        curr = node
        while curr.parent_id:
            if curr.parent_id == potential_ancestor.id:
                return True
            curr = self.mind_map.nodes.get(curr.parent_id)
            if not curr:
                break
        return False
