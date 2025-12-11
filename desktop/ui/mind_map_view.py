import math
import base64
from PySide6.QtWidgets import (QGraphicsView, QGraphicsScene, QGraphicsItem,
                               QGraphicsTextItem, QGraphicsRectItem, QGraphicsPathItem,
                               QMenu, QGraphicsSceneMouseEvent, QFileDialog, QGraphicsPixmapItem,
                               QInputDialog)
from PySide6.QtCore import Qt, QRectF, QPointF, Signal, QByteArray
from PySide6.QtGui import QPen, QBrush, QColor, QPainterPath, QFont, QPainter, QPixmap, QWheelEvent

from model import MindMap, MindMapNode, CrossLink
from utils import MindMapLayout

class NodeItem(QGraphicsRectItem):
    def __init__(self, node: MindMapNode, view):
        super().__init__()
        self.node = node
        self.view = view

        self.setRect(0, 0, node.width, node.height)
        self.setPos(node.x - node.width / 2, node.y - node.height / 2)

        # Style
        self.setBrush(QBrush(QColor(0xFF, 0xFF, 0xFF))) # White background for now
        if node.color_override:
             c = node.color_override
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

        # --- Content Rendering ---
        y_cursor = 10

        # 1. Images
        if node.images:
            # Display first image for now
            try:
                img_data = base64.b64decode(node.images[0])
                pixmap = QPixmap()
                pixmap.loadFromData(img_data)
                if not pixmap.isNull():
                    # Scale to fit width-20
                    target_w = node.width - 20
                    scaled_pix = pixmap.scaledToWidth(target_w, Qt.SmoothTransformation)

                    pix_item = QGraphicsPixmapItem(scaled_pix, self)
                    pix_item.setPos(10, y_cursor)
                    y_cursor += scaled_pix.height() + 5
            except Exception as e:
                print(f"Error loading image: {e}")

        # 2. Checkbox (if Todo)
        if node.is_todo:
            self.checkbox_rect = QRectF(10, y_cursor, 20, 20)
            y_cursor_text = y_cursor # Text aligns with checkbox top

            self.checkbox_item = QGraphicsRectItem(self.checkbox_rect, self)
            self.checkbox_item.setPen(QPen(Qt.black))
            if node.is_checked:
                self.checkbox_item.setBrush(QBrush(Qt.green))
            else:
                self.checkbox_item.setBrush(QBrush(Qt.white))

            # Text moves right
            text_x = 35
        else:
            text_x = 10
            y_cursor_text = y_cursor

        # 3. Text
        self.text_item = QGraphicsTextItem(node.text, self)
        self.text_item.setPos(text_x, y_cursor_text)
        self.text_item.setTextWidth(node.width - text_x - 10)

        y_cursor = max(y_cursor + 20, y_cursor_text + self.text_item.boundingRect().height() + 5)

        # 4. Tags
        if node.tags:
            tags_str = " ".join([f"#{t}" for t in node.tags])
            self.tags_item = QGraphicsTextItem(tags_str, self)
            f = self.tags_item.font()
            f.setPointSize(8)
            f.setItalic(True)
            self.tags_item.setFont(f)
            self.tags_item.setPos(10, y_cursor)
            self.tags_item.setTextWidth(node.width - 20)
            y_cursor += self.tags_item.boundingRect().height() + 5

        # 5. Note Indicator
        if node.note:
            self.note_icon = QGraphicsTextItem("📝", self)
            self.note_icon.setPos(node.width - 30, 0) # Top right corner

        # Collapse Indicator (if has children)
        if node.children:
            self.collapse_indicator = QGraphicsRectItem(node.width - 20, node.height / 2 - 5, 10, 10, self)
            if node.is_collapsed:
                self.collapse_indicator.setBrush(Qt.black)
            else:
                self.collapse_indicator.setBrush(Qt.white)
            self.collapse_indicator.setPen(QPen(Qt.black))

    def mousePressEvent(self, event):
        # Notify view of selection (explicitly for single clicks that might not trigger standard selection change if re-clicking)
        if event.button() == Qt.LeftButton:
             self.view.node_selected.emit(self.node)

        if event.button() == Qt.LeftButton:
            # Check for collapse click
            if hasattr(self, 'collapse_indicator') and self.collapse_indicator.isUnderMouse():
                self.view.toggle_collapse(self.node)
                event.accept()
                return

            # Check for Checkbox click - INDEPENDENT CHECK
            if self.node.is_todo and hasattr(self, 'checkbox_item') and self.checkbox_item.isUnderMouse():
                self.view.toggle_checkbox(self.node)
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
             self.view.refresh_scene()

    def contextMenuEvent(self, event):
        menu = QMenu()
        # "Edit Details" removed from context menu as we now have Dock Panel?
        # User said "detail manager... missing future is zoom".
        # Keeping context menu is fine, maybe rename to "Select in Detail View" or just keep it.
        # Let's keep common actions.

        if self.node.is_todo:
            toggle_chk = menu.addAction("Toggle Checked")

        add_child_action = menu.addAction("Add Child")
        add_link_action = menu.addAction("Add Crosslink")
        attach_img_action = menu.addAction("Attach Image")
        delete_action = menu.addAction("Delete")

        action = menu.exec(event.screenPos())

        if self.node.is_todo and action == toggle_chk:
            self.view.toggle_checkbox(self.node)
        elif action == add_child_action:
            self.view.add_child_node(self.node)
        elif action == add_link_action:
            self.view.start_crosslink(self.node)
        elif action == attach_img_action:
            self.view.attach_image(self.node)
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

class CrossLinkItem(QGraphicsPathItem):
    def __init__(self, link: CrossLink, start_pos, end_pos, view):
        super().__init__()
        self.link = link
        self.view = view

        path = QPainterPath()
        path.moveTo(start_pos)
        path.lineTo(end_pos)
        self.setPath(path)
        self.setPen(QPen(Qt.red, 2, Qt.DashLine))
        self.setZValue(-1)

        # Arrowhead
        self.arrow = QGraphicsPathItem(self)
        line = QPointF(end_pos.x() - start_pos.x(), end_pos.y() - start_pos.y())
        length = math.hypot(line.x(), line.y())
        if length > 0:
            norm = QPointF(line.x()/length, line.y()/length)
            angle = math.atan2(norm.y(), norm.x())
            arrow_p = QPainterPath()
            arrow_p.moveTo(end_pos)
            # -20px back
            p1 = QPointF(end_pos.x() - 15 * math.cos(angle - math.pi/6),
                         end_pos.y() - 15 * math.sin(angle - math.pi/6))
            p2 = QPointF(end_pos.x() - 15 * math.cos(angle + math.pi/6),
                         end_pos.y() - 15 * math.sin(angle + math.pi/6))
            arrow_p.lineTo(p1)
            arrow_p.lineTo(p2)
            arrow_p.closeSubpath()
            self.arrow.setPath(arrow_p)
            self.arrow.setBrush(Qt.red)
            self.arrow.setPen(Qt.NoPen)

        # Label
        if link.label:
            mid = QPointF((start_pos.x() + end_pos.x())/2, (start_pos.y() + end_pos.y())/2)
            txt = QGraphicsTextItem(link.label, self)
            txt.setDefaultTextColor(Qt.red)
            txt.setPos(mid)

        self.setFlag(QGraphicsItem.ItemIsSelectable)

    def contextMenuEvent(self, event):
        menu = QMenu()
        edit_action = menu.addAction("Edit Label")
        delete_action = menu.addAction("Delete")

        action = menu.exec(event.screenPos())

        if action == edit_action:
            self.view.edit_crosslink_label(self.link)
        elif action == delete_action:
            self.view.delete_crosslink(self.link)

class MindMapView(QGraphicsView):
    node_selected = Signal(MindMapNode) # Signal to notify panels

    def __init__(self, main_window):
        super().__init__()
        self.main_window = main_window
        self.scene = QGraphicsScene(self)
        self.setScene(self.scene)
        self.setRenderHint(QPainter.Antialiasing)
        self.setDragMode(QGraphicsView.ScrollHandDrag)

        # Enable Mouse Tracking for hover?
        # self.setMouseTracking(True)

        self.mind_map: MindMap = None
        self.crosslink_source: MindMapNode = None

    def set_mind_map(self, mind_map: MindMap):
        self.mind_map = mind_map
        self.refresh_scene()

    def wheelEvent(self, event: QWheelEvent):
        # Zoom with Ctrl + Wheel or just Wheel?
        # Standard behaviour usually just wheel for zoom if not scrolling,
        # but ScrollHandDrag usually means we drag to pan.
        # Let's support Ctrl+Wheel for zoom or just Wheel if drag mode active?

        # If modifiers
        if event.modifiers() & Qt.ControlModifier:
            zoom_in = event.angleDelta().y() > 0
            factor = 1.2 if zoom_in else 1 / 1.2
            self.scale(factor, factor)
            event.accept()
        else:
            super().wheelEvent(event)

    def refresh_scene(self):
        if not self.mind_map:
            self.scene.clear()
            return

        # 1. Run Layout
        MindMapLayout.layout(self.mind_map)

        # 2. Clear and Draw
        self.scene.clear()

        # Draw Edges first
        self.draw_edges(self.mind_map.nodes[self.mind_map.root_node_id])

        # Draw CrossLinks
        for link in self.mind_map.cross_links:
            start = self.mind_map.nodes.get(link.start_node_id)
            end = self.mind_map.nodes.get(link.end_node_id)
            if start and end and self.is_node_visible(start) and self.is_node_visible(end):
                item = CrossLinkItem(link, QPointF(start.x, start.y), QPointF(end.x, end.y), self)
                self.scene.addItem(item)

        # Draw Nodes
        for node in self.mind_map.nodes.values():
            if self.is_node_visible(node):
                item = NodeItem(node, self)
                # If selecting for crosslink
                if self.crosslink_source and node.id == self.crosslink_source.id:
                    item.setPen(QPen(Qt.red, 3))
                self.scene.addItem(item)

        # Update Scene Rect
        self.scene.setSceneRect(self.scene.itemsBoundingRect())

        # Overlay instruction if crosslinking
        if self.crosslink_source:
            txt = self.scene.addText("Click target node to link")
            txt.setDefaultTextColor(Qt.red)
            txt.setScale(2)
            txt.setPos(self.scene.sceneRect().topLeft())

    def mousePressEvent(self, event):
        # Clear selection signal if clicked background
        if not self.itemAt(event.pos()):
            self.node_selected.emit(None)

        if self.crosslink_source:
             item = self.itemAt(event.pos())
             if isinstance(item, NodeItem):
                 if item.node.id != self.crosslink_source.id:
                     self.create_crosslink(self.crosslink_source, item.node)
             elif isinstance(item, QGraphicsTextItem) and isinstance(item.parentItem(), NodeItem):
                 # Clicked text of node
                 if item.parentItem().node.id != self.crosslink_source.id:
                     self.create_crosslink(self.crosslink_source, item.parentItem().node)

             # Cancel if clicked background
             if not item:
                 self.crosslink_source = None
                 self.refresh_scene()
             return

        super().mousePressEvent(event)

    def is_node_visible(self, node: MindMapNode):
        curr = node
        while curr.parent_id:
            parent = self.mind_map.nodes.get(curr.parent_id)
            if not parent:
                return True
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

    def toggle_checkbox(self, node: MindMapNode):
        node.is_checked = not node.is_checked
        self.main_window.save_current_map()
        self.refresh_scene()

    def add_child_node(self, parent: MindMapNode):
        new_node = MindMapNode(text="New Child", parent_id=parent.id)
        new_node.x = parent.x + 50
        new_node.y = parent.y + 50
        self.mind_map.nodes[new_node.id] = new_node
        parent.children.append(new_node.id)
        parent.is_collapsed = False
        self.main_window.save_current_map()
        self.refresh_scene()

    def delete_node(self, node: MindMapNode):
        if node.id == self.mind_map.root_node_id:
            return
        if node.parent_id:
            parent = self.mind_map.nodes.get(node.parent_id)
            if parent and node.id in parent.children:
                parent.children.remove(node.id)
        self.remove_subtree(node)
        self.main_window.save_current_map()
        self.refresh_scene()
        self.node_selected.emit(None) # Deselect deleted

    def remove_subtree(self, node: MindMapNode):
        for child_id in list(node.children):
            child = self.mind_map.nodes.get(child_id)
            if child:
                self.remove_subtree(child)
        if node.id in self.mind_map.nodes:
            del self.mind_map.nodes[node.id]

    def reparent_node(self, node: MindMapNode, new_parent: MindMapNode):
        if self.is_descendant(node, new_parent):
            return
        if node.parent_id:
            old_parent = self.mind_map.nodes.get(node.parent_id)
            if old_parent and node.id in old_parent.children:
                old_parent.children.remove(node.id)
        node.parent_id = new_parent.id
        new_parent.children.append(node.id)
        new_parent.is_collapsed = False
        self.main_window.save_current_map()
        # Scene refresh triggered by mouseRelease

    def is_descendant(self, potential_ancestor: MindMapNode, node: MindMapNode):
        if node.id == potential_ancestor.id:
            return True
        curr = node
        while curr.parent_id:
            if curr.parent_id == potential_ancestor.id:
                return True
            curr = self.mind_map.nodes.get(curr.parent_id)
            if not curr:
                break
        return False

    def start_crosslink(self, node: MindMapNode):
        self.crosslink_source = node
        self.refresh_scene()

    def create_crosslink(self, source: MindMapNode, target: MindMapNode):
        link = CrossLink(start_node_id=source.id, end_node_id=target.id)
        self.mind_map.cross_links.append(link)
        self.crosslink_source = None
        self.main_window.save_current_map()
        self.refresh_scene()

    def edit_crosslink_label(self, link: CrossLink):
        text, ok = QInputDialog.getText(self, "Edit Link Label", "Label:", text=link.label)
        if ok:
            link.label = text
            self.main_window.save_current_map()
            self.refresh_scene()

    def delete_crosslink(self, link: CrossLink):
        if link in self.mind_map.cross_links:
            self.mind_map.cross_links.remove(link)
            self.main_window.save_current_map()
            self.refresh_scene()

    def attach_image(self, node: MindMapNode):
        file_path, _ = QFileDialog.getOpenFileName(self, "Select Image", "", "Images (*.png *.jpg *.jpeg *.bmp)")
        if file_path:
            with open(file_path, "rb") as f:
                data = f.read()
                b64 = base64.b64encode(data).decode('utf-8')
                node.images.append(b64)
                self.main_window.save_current_map()
                self.refresh_scene()
