import os
import shutil
from PySide6.QtWidgets import (QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
                               QPushButton, QListWidget, QListWidgetItem, QFileDialog,
                               QMessageBox, QLabel, QSplitter, QToolBar)
from PySide6.QtCore import Qt, QSize
from PySide6.QtGui import QAction
from model import MindMap, MindMapNode
from utils import FileHelper
from ui.mind_map_view import MindMapView

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MindMap Desktop")
        self.resize(1000, 700)

        self.storage_path = os.path.expanduser("~/Documents/MindMaps")
        if not os.path.exists(self.storage_path):
            os.makedirs(self.storage_path)

        self.current_mind_map: MindMap = None

        # Toolbar
        toolbar = QToolBar("Main Toolbar")
        self.addToolBar(toolbar)

        self.layout_action = QAction("Switch Layout", self)
        self.layout_action.triggered.connect(self.toggle_layout)
        toolbar.addAction(self.layout_action)

        export_md_action = QAction("Export Markdown", self)
        export_md_action.triggered.connect(self.export_markdown)
        toolbar.addAction(export_md_action)

        # Central Widget
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QHBoxLayout(central_widget)

        # Splitter
        splitter = QSplitter(Qt.Horizontal)
        main_layout.addWidget(splitter)

        # Left Panel (Library)
        library_widget = QWidget()
        library_layout = QVBoxLayout(library_widget)

        library_label = QLabel("Library")
        library_layout.addWidget(library_label)

        self.map_list = QListWidget()
        self.map_list.itemClicked.connect(self.on_map_selected)
        library_layout.addWidget(self.map_list)

        btn_layout = QHBoxLayout()
        new_btn = QPushButton("New")
        new_btn.clicked.connect(self.create_new_map)
        btn_layout.addWidget(new_btn)

        import_btn = QPushButton("Import")
        import_btn.clicked.connect(self.import_map)
        btn_layout.addWidget(import_btn)

        library_layout.addLayout(btn_layout)

        splitter.addWidget(library_widget)

        # Right Panel (Mind Map View)
        self.mind_map_view = MindMapView(self)
        splitter.addWidget(self.mind_map_view)
        splitter.setSizes([250, 750])

        self.refresh_library()

    def refresh_library(self):
        self.map_list.clear()
        maps = FileHelper.list_mind_maps(self.storage_path)
        for m in maps:
            item = QListWidgetItem(m.title)
            item.setData(Qt.UserRole, m.id)
            self.map_list.addItem(item)

    def create_new_map(self):
        new_map = MindMap.create_default()
        FileHelper.save_mind_map(new_map, self.storage_path)
        self.refresh_library()
        self.load_map(new_map.id)

    def on_map_selected(self, item):
        map_id = item.data(Qt.UserRole)
        self.load_map(map_id)

    def load_map(self, map_id):
        filename = f"{map_id}.json"
        filepath = os.path.join(self.storage_path, filename)
        if os.path.exists(filepath):
            self.current_mind_map = FileHelper.load_mind_map(filepath)
            self.mind_map_view.set_mind_map(self.current_mind_map)

    def import_map(self):
        file_path, _ = QFileDialog.getOpenFileName(self, "Import Mind Map", "", "JSON Files (*.json)")
        if not file_path:
            return

        try:
            imported_map = FileHelper.load_mind_map(file_path)

            existing_filename = f"{imported_map.id}.json"
            existing_filepath = os.path.join(self.storage_path, existing_filename)

            should_save = True

            if os.path.exists(existing_filepath):
                existing_map = FileHelper.load_mind_map(existing_filepath)
                if imported_map.last_modified <= existing_map.last_modified:
                    reply = QMessageBox.question(
                        self,
                        "Map Exists",
                        "A map with this ID exists and is newer or same age. Overwrite?",
                        QMessageBox.Yes | QMessageBox.No
                    )
                    if reply == QMessageBox.No:
                        should_save = False

            if should_save:
                FileHelper.save_mind_map(imported_map, self.storage_path)
                self.refresh_library()
                QMessageBox.information(self, "Success", "Mind Map imported successfully.")

        except Exception as e:
            QMessageBox.critical(self, "Error", f"Failed to import map: {e}")

    def save_current_map(self):
        if self.current_mind_map:
            FileHelper.save_mind_map(self.current_mind_map, self.storage_path)

    def toggle_layout(self):
        if not self.current_mind_map:
            return

        if self.current_mind_map.layout_type == "RADIAL":
            self.current_mind_map.layout_type = "TREE"
        else:
            self.current_mind_map.layout_type = "RADIAL"

        self.save_current_map()
        self.mind_map_view.refresh_scene()

    def export_markdown(self):
        if not self.current_mind_map:
            return

        file_path, _ = QFileDialog.getSaveFileName(self, "Export Markdown", f"{self.current_mind_map.title}.md", "Markdown Files (*.md)")
        if file_path:
            try:
                md_content = FileHelper.export_to_markdown(self.current_mind_map)
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(md_content)
                QMessageBox.information(self, "Success", "Export successful.")
            except Exception as e:
                QMessageBox.critical(self, "Error", f"Failed to export: {e}")
