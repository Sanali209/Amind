# Feature Gap Analysis: Android vs Desktop

This report compares the functionality of the Android and Desktop versions of the MindMap application.

## Mismatches and Gaps

| Feature Category | Feature | Android Status | Desktop Status | Match? | Reason for Mismatch |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **History** | **Undo/Redo** | **Implemented.** Has distinct Undo/Redo buttons and logic in `EditorScreen`. | **Missing.** No Undo/Redo buttons or logic visible in `MainWindow` or `MindMapView`. | **No** | Desktop version lacks the history stack implementation and UI controls present in Android. |
| **UI/UX** | **Docking Panels** | **N/A** (Uses Mobile Screens) | **Implemented.** Uses `QDockWidget` for Library and Detail Manager. | **No** | Platform-specific UI paradigm. Desktop uses multiple windows/panels, Android uses navigation screens. This is an intentional design choice, not a functional deficit. |
| **Editing** | **Node Details** | **Separate Screen.** `NoteEditorScreen` (implied or separate modal). | **Docked Panel.** Non-modal `NodeDetailPanel`. | **No** | UX divergence suitable for platform. Desktop panel allows viewing context while editing. |
| **Interaction** | **Zoom** | **Gestures.** Pinch-to-zoom. | **Toolbar/Mouse.** Buttons and Ctrl+Wheel. | **Yes** | Functionally matched, though interaction method differs by platform capabilities. |
| **Sharing** | **Share Intent** | **Implemented.** Uses Android Intents (`ACTION_SEND`). | **File Export.** Uses File Dialogs. | **Yes** | Mechanism differs due to OS (Intents vs File System), but core "Get data out" capability exists. |
| **Import** | **Import Intent** | **Implemented.** `ACTION_VIEW` intent. | **File Import.** Button + Dialog. | **Yes** | Android relies on external file managers; Desktop has integrated library management. |

## Matched Features

The following features have been successfully synchronized across both platforms:

*   **Data Model:** Both support Text, Notes, Tags, Colors, Todo Checkboxes, Images, and Layout Type.
*   **Layout Engine:** Both support 'Radial' and 'Tree' layouts with identical algorithms.
*   **Rendering:** Both render nodes, connections, cross-links, embedded images, and checkboxes.
*   **Export:** Both support exporting to Markdown.
*   **Cross-Links:** Both support creating, editing (Desktop context menu / Android dialog), and deleting cross-links.

## Recommendations

1.  **Implement Undo/Redo on Desktop:** The most significant functional gap is the lack of Undo/Redo on the Desktop version. The logic from Android (Snapshotting JSON to a stack) can be ported to Python.
2.  **Harmonize Editing Flows:** While the Dock is excellent for Desktop, ensuring the Android "Edit" flow exposes all the same fields conveniently (images, todo, etc.) is crucial. Current Android `EditorScreen` does this via a Dialog, which is matched.
