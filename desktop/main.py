import sys
import os
from PySide6.QtWidgets import QApplication
from ui.main_window import MainWindow

def main():
    app = QApplication(sys.argv)

    # Ensure storage directory exists
    storage_path = os.path.expanduser("~/Documents/MindMaps")
    if not os.path.exists(storage_path):
        os.makedirs(storage_path)

    window = MainWindow()
    window.show()

    sys.exit(app.exec())

if __name__ == "__main__":
    main()
