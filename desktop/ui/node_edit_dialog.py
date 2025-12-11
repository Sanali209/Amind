from PySide6.QtWidgets import (QDialog, QVBoxLayout, QHBoxLayout, QLabel,
                               QLineEdit, QTextEdit, QPushButton, QCheckBox,
                               QColorDialog, QDialogButtonBox)
from PySide6.QtGui import QColor
from PySide6.QtCore import Qt
from model import MindMapNode

class NodeEditDialog(QDialog):
    def __init__(self, parent, node: MindMapNode):
        super().__init__(parent)
        self.setWindowTitle("Edit Node")
        self.node = node
        self.result_node = None # Will store updated values if accepted

        layout = QVBoxLayout(self)

        # Text
        layout.addWidget(QLabel("Text:"))
        self.text_edit = QLineEdit(node.text)
        layout.addWidget(self.text_edit)

        # Note
        layout.addWidget(QLabel("Note:"))
        self.note_edit = QTextEdit()
        if node.note:
            self.note_edit.setPlainText(node.note)
        layout.addWidget(self.note_edit)

        # Tags
        layout.addWidget(QLabel("Tags (comma separated):"))
        tags_str = ", ".join(node.tags)
        self.tags_edit = QLineEdit(tags_str)
        layout.addWidget(self.tags_edit)

        # Todo
        self.todo_chk = QCheckBox("Is Todo Item")
        self.todo_chk.setChecked(node.is_todo)
        layout.addWidget(self.todo_chk)

        # Color
        h_layout = QHBoxLayout()
        self.color_btn = QPushButton("Select Color")
        self.color_btn.clicked.connect(self.choose_color)
        self.clear_color_btn = QPushButton("Clear Color")
        self.clear_color_btn.clicked.connect(self.clear_color)

        self.current_color = node.color_override
        self.color_preview = QLabel("   ")
        self.color_preview.setAutoFillBackground(True)
        self.update_color_preview()

        h_layout.addWidget(self.color_btn)
        h_layout.addWidget(self.clear_color_btn)
        h_layout.addWidget(self.color_preview)
        layout.addLayout(h_layout)

        # Buttons
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def choose_color(self):
        color = QColorDialog.getColor()
        if color.isValid():
            # Convert QColor to int ARGB (Android style)
            # Alpha is usually 255 (0xFF)
            # We store as signed int in Android, but unsigned in Python usually ok if we handle conversion
            # In Python model, color_override is int.
            # 0xAARRGGBB
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

    def get_values(self):
        # Return a dict or update node directly?
        # Let's return dict to apply
        tags = [t.strip() for t in self.tags_edit.text().split(",") if t.strip()]
        return {
            "text": self.text_edit.text(),
            "note": self.note_edit.toPlainText(),
            "tags": tags,
            "is_todo": self.todo_chk.isChecked(),
            "color_override": self.current_color
        }
