import uuid
import time
from typing import List, Dict, Optional

class MindMapNode:
    def __init__(self,
                 id: str = None,
                 text: str = "New Node",
                 note: str = None,
                 children: List[str] = None,
                 parent_id: str = None,
                 x: float = 0.0,
                 y: float = 0.0,
                 width: float = 100.0,
                 height: float = 100.0,
                 color: int = 0xFF000000,
                 color_override: Optional[int] = None,
                 tags: List[str] = None,
                 is_collapsed: bool = False,
                 images: List[str] = None,
                 is_todo: bool = False,
                 is_checked: bool = False):
        self.id = id if id else str(uuid.uuid4())
        self.text = text
        self.note = note
        self.children = children if children is not None else []
        self.parent_id = parent_id
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.color = color
        self.color_override = color_override
        self.tags = tags if tags is not None else []
        self.is_collapsed = is_collapsed
        self.images = images if images is not None else []
        self.is_todo = is_todo
        self.is_checked = is_checked

    def to_dict(self):
        return {
            "id": self.id,
            "text": self.text,
            "note": self.note,
            "children": self.children,
            "parentId": self.parent_id,
            "x": self.x,
            "y": self.y,
            "width": self.width,
            "height": self.height,
            "color": self.color,
            "colorOverride": self.color_override,
            "tags": self.tags,
            "isCollapsed": self.is_collapsed,
            "images": self.images,
            "isTodo": self.is_todo,
            "isChecked": self.is_checked
        }

    @classmethod
    def from_dict(cls, data):
        return cls(
            id=data.get("id"),
            text=data.get("text", "New Node"),
            note=data.get("note"),
            children=data.get("children", []),
            parent_id=data.get("parentId"),
            x=float(data.get("x", 0.0)),
            y=float(data.get("y", 0.0)),
            width=float(data.get("width", 100.0)),
            height=float(data.get("height", 100.0)),
            color=data.get("color", 0xFF000000),
            color_override=data.get("colorOverride"),
            tags=data.get("tags", []),
            is_collapsed=data.get("isCollapsed", False),
            images=data.get("images", []),
            is_todo=data.get("isTodo", False),
            is_checked=data.get("isChecked", False)
        )

class CrossLink:
    def __init__(self,
                 id: str = None,
                 start_node_id: str = "",
                 end_node_id: str = "",
                 label: str = None,
                 note: str = None):
        self.id = id if id else str(uuid.uuid4())
        self.start_node_id = start_node_id
        self.end_node_id = end_node_id
        self.label = label
        self.note = note

    def to_dict(self):
        return {
            "id": self.id,
            "startNodeId": self.start_node_id,
            "endNodeId": self.end_node_id,
            "label": self.label,
            "note": self.note
        }

    @classmethod
    def from_dict(cls, data):
        return cls(
            id=data.get("id"),
            start_node_id=data.get("startNodeId", ""),
            end_node_id=data.get("endNodeId", ""),
            label=data.get("label"),
            note=data.get("note")
        )

class MindMap:
    def __init__(self,
                 id: str = None,
                 title: str = "New Mind Map",
                 root_node_id: str = "",
                 nodes: Dict[str, MindMapNode] = None,
                 cross_links: List[CrossLink] = None,
                 last_modified: int = None):
        self.id = id if id else str(uuid.uuid4())
        self.title = title
        self.root_node_id = root_node_id
        self.nodes = nodes if nodes is not None else {}
        self.cross_links = cross_links if cross_links is not None else []
        self.last_modified = last_modified if last_modified else int(time.time() * 1000)

    @classmethod
    def create_default(cls):
        root = MindMapNode(text="Central Idea")
        return cls(
            root_node_id=root.id,
            nodes={root.id: root}
        )

    def to_dict(self):
        return {
            "id": self.id,
            "title": self.title,
            "rootNodeId": self.root_node_id,
            "nodes": {k: v.to_dict() for k, v in self.nodes.items()},
            "crossLinks": [link.to_dict() for link in self.cross_links],
            "lastModified": self.last_modified
        }

    @classmethod
    def from_dict(cls, data):
        nodes_data = data.get("nodes", {})
        nodes = {k: MindMapNode.from_dict(v) for k, v in nodes_data.items()}

        links_data = data.get("crossLinks", [])
        links = [CrossLink.from_dict(l) for l in links_data]

        return cls(
            id=data.get("id"),
            title=data.get("title", "New Mind Map"),
            root_node_id=data.get("rootNodeId", ""),
            nodes=nodes,
            cross_links=links,
            last_modified=data.get("lastModified", int(time.time() * 1000))
        )
