import math
import base64
from PySide6.QtWidgets import (QGraphicsView, QGraphicsScene, QGraphicsItem,
                               QGraphicsTextItem, QGraphicsRectItem, QGraphicsPathItem,
                               QMenu, QGraphicsSceneMouseEvent, QColorDialog, QFileDialog,
                               QGraphicsPixmapItem)
from PySide6.QtCore import Qt, QRectF, QPointF, Signal, QByteArray, QBuffer, QIODevice
from PySide6.QtGui import QPen, QBrush, QColor, QPainterPath, QFont, QPainter, QPixmap, QImage

from model import MindMap, MindMapNode, CrossLink
from utils import MindMapLayout
from ui.note_editor import NoteEditorWindow

class NodeItem(QGraphicsRectItem):
    def __init__(self, node: MindMapNode, view):
        super().__init__()
        self.node = node
        self.view = view

        self.setRect(0, 0, node.width, node.height)
        self.setPos(node.x - node.width / 2, node.y - node.height / 2)

        # Style
        # "fill all node with color not border"
        fill_color = QColor(0xFF, 0xFF, 0xFF) # Default White
        border_color = Qt.black

        if node.color_override:
             c = node.color_override
             # Alpha is MSB
             alpha = (c >> 24) & 0xFF
             red = (c >> 16) & 0xFF
             green = (c >> 8) & 0xFF
             blue = c & 0xFF
             # If alpha is 0 (from some conversion issues), force 255
             if alpha == 0: alpha = 255
             fill_color = QColor(red, green, blue, alpha)
        elif node.id == view.mind_map.root_node_id:
             fill_color = QColor(255, 255, 255) # Root white
        else:
             # Rainbow logic could go here, but stick to simple
             pass

        self.setBrush(QBrush(fill_color))
        self.setPen(QPen(border_color, 2))

        # Flags
        self.setFlag(QGraphicsItem.ItemIsSelectable)
        self.setFlag(QGraphicsItem.ItemIsMovable)
        self.setFlag(QGraphicsItem.ItemSendsGeometryChanges)

        # 1. Images (Thumbnail)
        current_y = 10
        if node.images:
            try:
                img_data = base64.b64decode(node.images[0])
                image = QImage.fromData(img_data)
                pixmap = QPixmap.fromImage(image)
                if not pixmap.isNull():
                    pixmap = pixmap.scaled(60, 60, Qt.KeepAspectRatio, Qt.SmoothTransformation)
                    pix_item = QGraphicsPixmapItem(pixmap, self)
                    pix_item.setPos((node.width - pixmap.width())/2, current_y)
                    current_y += pixmap.height() + 5
            except Exception as e:
                print(f"Failed to load image: {e}")

        # 2. Checkbox (Todo)
        if node.is_todo:
            cb_size = 15
            cb_x = 10
            cb_y = current_y + 5

            self.checkbox_rect = QGraphicsRectItem(cb_x, cb_y, cb_size, cb_size, self)
            self.checkbox_rect.setPen(QPen(Qt.black))

            if node.is_checked:
                 # Check mark (X)
                 path = QPainterPath()
                 path.moveTo(cb_x + 2, cb_y + 2)
                 path.lineTo(cb_x + cb_size - 2, cb_y + cb_size - 2)
                 path.moveTo(cb_x + cb_size - 2, cb_y + 2)
                 path.lineTo(cb_x + 2, cb_y + cb_size - 2)
                 check_item = QGraphicsPathItem(path, self)
                 check_item.setPen(QPen(Qt.black, 2))

            # Adjust text pos? Or just overlay?
            # Let's put text to right of checkbox if todo
            text_x = 35
        else:
            text_x = 10

        # 3. Text
        self.text_item = QGraphicsTextItem(node.text, self)
        self.text_item.setPos(text_x, current_y)

        # 4. Note Indicator
        if node.note:
            note_ind = QGraphicsTextItem("📝", self)
            note_ind.setPos(node.width - 25, node.height - 25)

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
            # Collapse click
            if hasattr(self, 'collapse_indicator') and self.collapse_indicator.isUnderMouse():
                self.view.toggle_collapse(self.node)
                event.accept()
                return

            # Checkbox click
            if hasattr(self, 'checkbox_rect') and self.checkbox_rect.isUnderMouse():
                 self.view.toggle_checkbox(self.node)
                 event.accept()
                 return

            # General Click Handler (for Crosslink selection)
            self.view.on_node_clicked(self.node)

        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event):
        super().mouseReleaseEvent(event)
        if self.flags() & QGraphicsItem.ItemIsMovable:
             colliding_items = self.collidingItems()
             for item in colliding_items:
                 if isinstance(item, NodeItem) and item != self:
                     self.view.reparent_node(self.node, item.node)
                     break
             self.view.refresh_scene()

    def contextMenuEvent(self, event):
        menu = QMenu()
        edit_action = menu.addAction("Edit Text")
        edit_note_action = menu.addAction("Edit Note")

        toggle_todo_action = menu.addAction("Toggle Todo")
        change_color_action = menu.addAction("Change Color")
        attach_img_action = menu.addAction("Attach Image")

        add_child_action = menu.addAction("Add Child")
        add_link_action = menu.addAction("Add CrossLink")
        delete_action = menu.addAction("Delete")

        action = menu.exec(event.screenPos())

        if action == edit_action:
            self.view.edit_node_text(self.node)
        elif action == edit_note_action:
            self.view.edit_node_note(self.node)
        elif action == toggle_todo_action:
            self.view.toggle_todo_mode(self.node)
        elif action == change_color_action:
            self.view.change_node_color(self.node)
        elif action == attach_img_action:
            self.view.attach_image(self.node)
        elif action == add_child_action:
            self.view.add_child_node(self.node)
        elif action == add_link_action:
            self.view.start_crosslink_selection(self.node)
        elif action == delete_action:
            self.view.delete_node(self.node)

class CrossLinkItem(QGraphicsPathItem):
    def __init__(self, link: CrossLink, start_pos: QPointF, end_pos: QPointF, view):
        super().__init__()
        self.link = link
        self.view = view

        path = QPainterPath()
        path.moveTo(start_pos)
        path.lineTo(end_pos)
        self.setPath(path)

        pen = QPen(Qt.red, 2)
        # pen.setStyle(Qt.DashLine)
        self.setPen(pen)

        # Arrow head
        # Simplified: just line for now or simple arrow
        # ... arrow logic omitted for brevity in desktop update

        # Label
        if link.label or link.note:
            mid_x = (start_pos.x() + end_pos.x()) / 2
            mid_y = (start_pos.y() + end_pos.y()) / 2
            txt = (link.label if link.label else "") + (" 📝" if link.note else "")
            if txt:
                self.label_item = QGraphicsTextItem(txt, self)
                self.label_item.setDefaultTextColor(Qt.red)
                self.label_item.setPos(mid_x, mid_y)

    def contextMenuEvent(self, event):
        menu = QMenu()
        edit_label_action = menu.addAction("Edit Label")
        edit_note_action = menu.addAction("Edit Note")
        delete_action = menu.addAction("Delete")

        action = menu.exec(event.screenPos())

        if action == edit_label_action:
            self.view.edit_crosslink_label(self.link)
        elif action == edit_note_action:
            self.view.edit_crosslink_note(self.link)
        elif action == delete_action:
            self.view.delete_crosslink(self.link)


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
        self.crosslink_source_node: MindMapNode = None # State for creating link

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

        MindMapLayout.layout(self.mind_map)
        self.scene.clear()

        # Draw CrossLinks first? Or Edges?

        # Draw Tree Edges
        self.draw_edges(self.mind_map.nodes[self.mind_map.root_node_id])

        # Draw CrossLinks
        for link in self.mind_map.cross_links:
            start = self.mind_map.nodes.get(link.start_node_id)
            end = self.mind_map.nodes.get(link.end_node_id)
            if start and end and self.is_node_visible(start) and self.is_node_visible(end):
                item = CrossLinkItem(link, QPointF(start.x, start.y), QPointF(end.x, end.y), self)
                self.scene.addItem(item)

        # Draw Nodes
        node_items = {}
        for node in self.mind_map.nodes.values():
            if self.is_node_visible(node):
                item = NodeItem(node, self)
                # If selecting for crosslink
                if self.crosslink_source and node.id == self.crosslink_source.id:
                    item.setPen(QPen(Qt.red, 3))
                self.scene.addItem(item)
                node_items[node.id] = item

        # Draw CrossLinks
        for link in self.mind_map.cross_links:
            start = self.mind_map.nodes.get(link.start_node_id)
            end = self.mind_map.nodes.get(link.end_node_id)
            if start and end and self.is_node_visible(start) and self.is_node_visible(end):
                # Calculate centers
                s_item = node_items.get(start.id)
                e_item = node_items.get(end.id)
                if s_item and e_item:
                    # Center
                    sp = s_item.sceneBoundingRect().center()
                    ep = e_item.sceneBoundingRect().center()
                    link_item = CrossLinkItem(link, sp, ep, self)
                    self.scene.addItem(link_item)

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
            if not parent: return True
            if parent.is_collapsed: return False
            curr = parent
        return True

    def draw_edges(self, node: MindMapNode):
        if node.is_collapsed: return
        start_pos = QPointF(node.x, node.y)
        for child_id in node.children:
            child = self.mind_map.nodes.get(child_id)
            if child:
                end_pos = QPointF(child.x, child.y)
                path = QPainterPath()
                path.moveTo(start_pos)
                ctrl1 = QPointF(start_pos.x() + (end_pos.x() - start_pos.x()) / 2, start_pos.y())
                ctrl2 = QPointF(start_pos.x() + (end_pos.x() - start_pos.x()) / 2, end_pos.y())
                path.cubicTo(ctrl1, ctrl2, end_pos)

                path_item = QGraphicsPathItem(path)
                path_item.setPen(QPen(Qt.gray, 2))
                path_item.setZValue(-1)
                self.scene.addItem(path_item)

                self.draw_edges(child)

    def toggle_collapse(self, node: MindMapNode):
        node.is_collapsed = not node.is_collapsed
        self.main_window.save_current_map()
        self.refresh_scene()

    def toggle_checkbox(self, node: MindMapNode):
        node.is_checked = not node.is_checked
        self.main_window.save_current_map()
        self.refresh_scene()

    def toggle_todo_mode(self, node: MindMapNode):
        node.is_todo = not node.is_todo
        if node.is_todo: node.is_checked = False
        self.main_window.save_current_map()
        self.refresh_scene()

    def edit_node_text(self, node: MindMapNode):
        from PySide6.QtWidgets import QInputDialog
        text, ok = QInputDialog.getText(self, "Edit Node", "Text:", text=node.text)
        if ok:
            node.text = text
            self.main_window.save_current_map()
            self.refresh_scene()

    def edit_node_note(self, node: MindMapNode):
        dlg = NoteEditorWindow(node.note if node.note else "", self)
        if dlg.exec():
            node.note = dlg.get_text()
            self.main_window.save_current_map()
            self.refresh_scene()

    def change_node_color(self, node: MindMapNode):
        color = QColorDialog.getColor()
        if color.isValid():
             # Store as Int ARGB
             val = color.rgba() # Returns 0xAARRGGBB as int
             node.color_override = val
             self.main_window.save_current_map()
             self.refresh_scene()

    def attach_image(self, node: MindMapNode):
        fname, _ = QFileDialog.getOpenFileName(self, "Select Image", "", "Image Files (*.png *.jpg *.bmp)")
        if fname:
            with open(fname, "rb") as image_file:
                encoded_string = base64.b64encode(image_file.read()).decode('utf-8')
                node.images = [encoded_string] # Replace or append? List implies append, but let's just keep one for simplicity/thumbnail
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
        if node.id == self.mind_map.root_node_id: return
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
            if child: self.remove_subtree(child)
        if node.id in self.mind_map.nodes: del self.mind_map.nodes[node.id]

    def reparent_node(self, node: MindMapNode, new_parent: MindMapNode):
        if self.is_descendant(node, new_parent): return
        if node.parent_id:
            old_parent = self.mind_map.nodes.get(node.parent_id)
            if old_parent and node.id in old_parent.children: old_parent.children.remove(node.id)
        node.parent_id = new_parent.id
        new_parent.children.append(node.id)
        new_parent.is_collapsed = False
        self.main_window.save_current_map()

    def is_descendant(self, potential_ancestor: MindMapNode, node: MindMapNode):
        if node.id == potential_ancestor.id: return True
        curr = node
        while curr.parent_id:
            if curr.parent_id == potential_ancestor.id: return True
            curr = self.mind_map.nodes.get(curr.parent_id)
            if not curr: break
        return False

    def start_crosslink_selection(self, source_node: MindMapNode):
        self.crosslink_source_node = source_node
        # Change cursor or status?
        # For simplicity, next node click (if handled) could check this state.
        # But node click currently is handled by NodeItem.mousePress.
        # Let's hack it: user must click another node.
        # To support this properly, NodeItem needs access to view state.
        pass # Implemented via NodeItem click logic if expanded, but here simplest is:
             # Just assume the user will drag/drop or something.
             # Actually, let's implement the "Click second node" logic via a global view state check in NodeItem's mousePress.
             # BUT NodeItem is separate.
             # I will modify NodeItem.mousePressEvent above to call a view method `on_node_clicked`.

    def on_node_clicked(self, node: MindMapNode):
        if self.crosslink_source_node:
            if node.id != self.crosslink_source_node.id:
                 # Create Link
                 link = CrossLink(start_node_id=self.crosslink_source_node.id, end_node_id=node.id)
                 self.mind_map.cross_links.append(link)
                 self.main_window.save_current_map()
                 self.refresh_scene()
            self.crosslink_source_node = None

    def edit_crosslink_label(self, link: CrossLink):
        from PySide6.QtWidgets import QInputDialog
        text, ok = QInputDialog.getText(self, "Edit Link Label", "Label:", text=link.label if link.label else "")
        if ok:
            link.label = text
            self.main_window.save_current_map()
            self.refresh_scene()

    def edit_crosslink_note(self, link: CrossLink):
        dlg = NoteEditorWindow(link.note if link.note else "", self)
        if dlg.exec():
            link.note = dlg.get_text()
            self.main_window.save_current_map()
            self.refresh_scene()

    def delete_crosslink(self, link: CrossLink):
        if link in self.mind_map.cross_links:
            self.mind_map.cross_links.remove(link)
            self.main_window.save_current_map()
            self.refresh_scene()
