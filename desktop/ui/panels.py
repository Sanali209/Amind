from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QLabel,
                               QLineEdit, QTextEdit, QPushButton, QCheckBox,
                               QColorDialog, QListWidget, QListWidgetItem,
                               QFileDialog, QMessageBox)
from PySide6.QtGui import QColor
from PySide6.QtCore import Qt, Signal
from model import MindMapNode
from utils import FileHelper

class LibraryPanel(QWidget):
    map_selected = Signal(str) # map_id
    create_map_requested = Signal()
    import_map_requested = Signal()

    def __init__(self, storage_path):
        super().__init__()
        self.storage_path = storage_path

        layout = QVBoxLayout(self)

        # List
        self.map_list = QListWidget()
        self.map_list.itemClicked.connect(self.on_item_clicked)
        layout.addWidget(self.map_list)

        # Buttons
        btn_layout = QHBoxLayout()
        new_btn = QPushButton("New")
        new_btn.clicked.connect(self.create_map_requested.emit)
        btn_layout.addWidget(new_btn)

        import_btn = QPushButton("Import")
        import_btn.clicked.connect(self.import_map_requested.emit)
        btn_layout.addWidget(import_btn)

        layout.addLayout(btn_layout)

        self.refresh()

    def refresh(self):
        self.map_list.clear()
        maps = FileHelper.list_mind_maps(self.storage_path)
        for m in maps:
            item = QListWidgetItem(m.title)
            item.setData(Qt.UserRole, m.id)
            self.map_list.addItem(item)

    def on_item_clicked(self, item):
        map_id = item.data(Qt.UserRole)
        self.map_selected.emit(map_id)


class NodeDetailPanel(QWidget):
    node_updated = Signal(MindMapNode) # Emitted when changes applied

    def __init__(self):
        super().__init__()
        self.current_node = None
        self.layout = QVBoxLayout(self)

        # Text
        self.layout.addWidget(QLabel("Text:"))
        self.text_edit = QLineEdit()
        self.layout.addWidget(self.text_edit)

        # Note
        self.layout.addWidget(QLabel("Note:"))
        self.note_edit = QTextEdit()
        self.layout.addWidget(self.note_edit)

        # Tags
        self.layout.addWidget(QLabel("Tags (comma separated):"))
        self.tags_edit = QLineEdit()
        self.layout.addWidget(self.tags_edit)

        # Todo
        self.todo_chk = QCheckBox("Is Todo Item")
        self.layout.addWidget(self.todo_chk)

        # Color
        h_layout = QHBoxLayout()
        self.color_btn = QPushButton("Select Color")
        self.color_btn.clicked.connect(self.choose_color)
        self.clear_color_btn = QPushButton("Clear Color")
        self.clear_color_btn.clicked.connect(self.clear_color)

        self.color_preview = QLabel("   ")
        self.color_preview.setAutoFillBackground(True)
        self.current_color = None # Int
        self.update_color_preview()

        h_layout.addWidget(self.color_btn)
        h_layout.addWidget(self.clear_color_btn)
        h_layout.addWidget(self.color_preview)
        self.layout.addLayout(h_layout)

        # Apply Button (for now explicit apply, or could be auto)
        self.apply_btn = QPushButton("Apply Changes")
        self.apply_btn.clicked.connect(self.apply_changes)
        self.layout.addWidget(self.apply_btn)

        self.layout.addStretch()

        self.setEnabled(False) # Disabled until node selected

    def set_node(self, node: MindMapNode):
        self.current_node = node
        if not node:
            self.setEnabled(False)
            self.text_edit.clear()
            self.note_edit.clear()
            self.tags_edit.clear()
            self.todo_chk.setChecked(False)
            self.current_color = None
            self.update_color_preview()
            return

        self.setEnabled(True)
        self.text_edit.setText(node.text)
        self.note_edit.setPlainText(node.note if node.note else "")
        self.tags_edit.setText(", ".join(node.tags))
        self.todo_chk.setChecked(node.is_todo)
        self.current_color = node.color_override
        self.update_color_preview()

    def choose_color(self):
        color = QColorDialog.getColor()
        if color.isValid():
            val = (color.alpha() << 24) | (color.red() << 16) | (color.green() << 8) | color.blue()
            self.current_color = val
            self.update_color_preview()

    def clear_color(self):
        self.current_color = None
        self.update_color_preview()

    def update_color_preview(self):
        if self.current_color is not None:
             c = self.current_color
             a = (c >> 24) & 0xFF
             r = (c >> 16) & 0xFF
             g = (c >> 8) & 0xFF
             b = c & 0xFF
             qc = QColor(r, g, b, a)
             pal = self.color_preview.palette()
             pal.setColor(self.color_preview.backgroundRole(), qc)
             self.color_preview.setPalette(pal)
             self.color_preview.setText("   ")
        else:
             pal = self.color_preview.palette()
             pal.setColor(self.color_preview.backgroundRole(), Qt.white)
             self.color_preview.setPalette(pal)
             self.color_preview.setText("None")

    def apply_changes(self):
        if self.current_node:
            self.current_node.text = self.text_edit.text()
            self.current_node.note = self.note_edit.toPlainText()

            tags_raw = self.tags_edit.text().split(",")
            self.current_node.tags = [t.strip() for t in tags_raw if t.strip()]

            self.current_node.is_todo = self.todo_chk.isChecked()
            self.current_node.color_override = self.current_color

            self.node_updated.emit(self.current_node)
