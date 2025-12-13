from PySide6.QtWidgets import (QDialog, QVBoxLayout, QTextEdit, QHBoxLayout,
                               QPushButton, QToolBar)
from PySide6.QtGui import QAction, QIcon

class NoteEditorWindow(QDialog):
    def __init__(self, initial_text="", parent=None):
        super().__init__(parent)
        self.setWindowTitle("Note Editor - Markdown")
        self.resize(600, 400)
        self.text_content = initial_text
        self.result_text = initial_text

        layout = QVBoxLayout(self)

        # Toolbar
        toolbar = QToolBar()
        layout.addWidget(toolbar)

        # Simple actions
        bold_action = QAction("Bold", self)
        bold_action.triggered.connect(lambda: self.insert_md("**", "**"))
        toolbar.addAction(bold_action)

        italic_action = QAction("Italic", self)
        italic_action.triggered.connect(lambda: self.insert_md("*", "*"))
        toolbar.addAction(italic_action)

        h1_action = QAction("H1", self)
        h1_action.triggered.connect(lambda: self.insert_md("# ", ""))
        toolbar.addAction(h1_action)

        list_action = QAction("List", self)
        list_action.triggered.connect(lambda: self.insert_md("- ", ""))
        toolbar.addAction(list_action)

        # Editor
        self.editor = QTextEdit()
        self.editor.setPlainText(initial_text)
        layout.addWidget(self.editor)

        # Buttons
        btn_layout = QHBoxLayout()
        save_btn = QPushButton("Save")
        save_btn.clicked.connect(self.save)
        cancel_btn = QPushButton("Cancel")
        cancel_btn.clicked.connect(self.reject)

        btn_layout.addWidget(save_btn)
        btn_layout.addWidget(cancel_btn)
        layout.addLayout(btn_layout)

    def insert_md(self, prefix, suffix):
        cursor = self.editor.textCursor()
        if cursor.hasSelection():
            text = cursor.selectedText()
            cursor.insertText(f"{prefix}{text}{suffix}")
        else:
            cursor.insertText(f"{prefix}text{suffix}")

    def save(self):
        self.result_text = self.editor.toPlainText()
        self.accept()

    def get_text(self):
        return self.result_text
