import os
import shutil
from PySide6.QtWidgets import (QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
                               QPushButton, QListWidget, QListWidgetItem, QFileDialog,
                               QMessageBox, QLabel, QSplitter, QToolBar, QDockWidget)
from PySide6.QtCore import Qt, QSize
from PySide6.QtGui import QAction, QIcon
from model import MindMap, MindMapNode
from utils import FileHelper
from ui.mind_map_view import MindMapView
from ui.panels import LibraryPanel, NodeDetailPanel

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MindMap Desktop")
        self.resize(1200, 800)

        self.storage_path = os.path.expanduser("~/Documents/MindMaps")
        if not os.path.exists(self.storage_path):
            os.makedirs(self.storage_path)

        self.current_mind_map: MindMap = None

        # --- Toolbar ---
        toolbar = QToolBar("Main Toolbar")
        self.addToolBar(toolbar)

        self.layout_action = QAction("Switch Layout", self)
        self.layout_action.triggered.connect(self.toggle_layout)
        toolbar.addAction(self.layout_action)

        export_md_action = QAction("Export Markdown", self)
        export_md_action.triggered.connect(self.export_markdown)
        toolbar.addAction(export_md_action)

        toolbar.addSeparator()

        zoom_in_action = QAction("Zoom In", self)
        zoom_in_action.triggered.connect(self.zoom_in)
        toolbar.addAction(zoom_in_action)

        zoom_out_action = QAction("Zoom Out", self)
        zoom_out_action.triggered.connect(self.zoom_out)
        toolbar.addAction(zoom_out_action)

        # --- Central Widget (Mind Map View) ---
        self.mind_map_view = MindMapView(self)
        self.setCentralWidget(self.mind_map_view)

        # Connect Selection Signal
        self.mind_map_view.node_selected.connect(self.on_node_selected)

        # --- Dock: Library ---
        self.library_dock = QDockWidget("Library", self)
        self.library_dock.setAllowedAreas(Qt.LeftDockWidgetArea | Qt.RightDockWidgetArea)
        self.library_panel = LibraryPanel(self.storage_path)

        # Right Panel (Mind Map View)
        right_panel = QWidget()
        right_layout = QVBoxLayout(right_panel)
        right_layout.setContentsMargins(0, 0, 0, 0)

        # Toolbar for Layout Switch
        toolbar = QHBoxLayout()
        toolbar.addStretch()

        self.layout_btn = QPushButton("Switch Layout")
        self.layout_btn.clicked.connect(self.toggle_layout)
        toolbar.addWidget(self.layout_btn)

        right_layout.addLayout(toolbar)

        self.mind_map_view = MindMapView(self)
        right_layout.addWidget(self.mind_map_view)

        splitter.addWidget(right_panel)
        splitter.setSizes([250, 750])

        self.detail_dock.setWidget(self.detail_panel)
        self.addDockWidget(Qt.RightDockWidgetArea, self.detail_dock)

    def toggle_layout(self):
        if self.current_mind_map:
             if self.current_mind_map.layout_type == "RADIAL":
                 self.current_mind_map.layout_type = "TREE"
             else:
                 self.current_mind_map.layout_type = "RADIAL"
             self.save_current_map()
             self.mind_map_view.refresh_scene()

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
        self.library_panel.refresh()
        self.load_map(new_map.id)

    def load_map(self, map_id):
        filename = f"{map_id}.json"
        filepath = os.path.join(self.storage_path, filename)
        if os.path.exists(filepath):
            self.current_mind_map = FileHelper.load_mind_map(filepath)
            self.mind_map_view.set_mind_map(self.current_mind_map)
            # Clear selection details
            self.detail_panel.set_node(None)

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
                self.library_panel.refresh()
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

    def on_node_selected(self, node):
        self.detail_panel.set_node(node)

    def on_node_updated(self, node):
        # Triggered by Detail Panel Apply
        if self.current_mind_map:
            if node.id == self.current_mind_map.root_node_id:
                self.current_mind_map.title = node.text
            self.save_current_map()
            self.mind_map_view.refresh_scene()

    def zoom_in(self):
        self.mind_map_view.scale(1.2, 1.2)

    def zoom_out(self):
        self.mind_map_view.scale(1/1.2, 1/1.2)
