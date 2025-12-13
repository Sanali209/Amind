import json
import os
import math
import time
from typing import Dict, List
from model import MindMap, MindMapNode

class FileHelper:
    @staticmethod
    def save_mind_map(mind_map: MindMap, directory: str):
        # Update timestamp on save
        mind_map.last_modified = int(time.time() * 1000)

        filename = f"{mind_map.id}.json"
        filepath = os.path.join(directory, filename)

        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(mind_map.to_dict(), f, indent=2, ensure_ascii=False)

    @staticmethod
    def load_mind_map(filepath: str) -> MindMap:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return MindMap.from_dict(data)

    @staticmethod
    def list_mind_maps(directory: str) -> List[MindMap]:
        if not os.path.exists(directory):
            return []

        maps = []
        for filename in os.listdir(directory):
            if filename.endswith(".json"):
                try:
                    filepath = os.path.join(directory, filename)
                    maps.append(FileHelper.load_mind_map(filepath))
                except Exception as e:
                    print(f"Error loading {filename}: {e}")

        # Sort by last modified descending
        return sorted(maps, key=lambda m: m.last_modified, reverse=True)

    @staticmethod
    def delete_mind_map(mind_map_id: str, directory: str):
        filename = f"{mind_map_id}.json"
        filepath = os.path.join(directory, filename)
        if os.path.exists(filepath):
            os.remove(filepath)

    @staticmethod
    def export_to_markdown(mind_map: MindMap) -> str:
        sb = []
        sb.append(f"# {mind_map.title}\n\n")

        root = mind_map.nodes.get(mind_map.root_node_id)
        if root:
            FileHelper.traverse_node_for_markdown(root, mind_map, 0, sb)

        # Append Crosslinks section if any
        if mind_map.cross_links:
            sb.append("\n## Cross Links\n")
            for link in mind_map.cross_links:
                start = mind_map.nodes.get(link.start_node_id)
                end = mind_map.nodes.get(link.end_node_id)
                if start and end:
                    label = link.label if link.label else "Link"
                    sb.append(f"- {start.text} --{label}--> {end.text}")
                    if link.note:
                        sb.append(f"\n  > {link.note}")
                    sb.append("\n")

        # Append Tags Index
        tags_map = {} # Tag -> List of Node Names
        for node in mind_map.nodes.values():
            for tag in node.tags:
                if tag not in tags_map:
                    tags_map[tag] = []
                tags_map[tag].append(node.text)

        if tags_map:
            sb.append("\n## Tags\n")
            for tag, nodes in tags_map.items():
                sb.append(f"- **#{tag}**: {', '.join(nodes)}\n")

        return "".join(sb)

    @staticmethod
    def traverse_node_for_markdown(node: MindMapNode, mind_map: MindMap, level: int, sb: List[str]):
        # Indentation
        indent = "  " * level
        sb.append(indent)

        # Checkbox logic
        if node.is_todo:
            mark = "x" if node.is_checked else " "
            sb.append(f"- [{mark}] {node.text}")
        else:
            sb.append(f"- {node.text}")

        # Tags inline?
        if node.tags:
            tags_str = " ".join([f"#{t}" for t in node.tags])
            sb.append(f" {tags_str}")
        sb.append("\n")

        # Notes as blockquote or just text under
        if node.note:
            sb.append(f"{indent}  > {node.note}\n")

        for child_id in node.children:
            child = mind_map.nodes.get(child_id)
            if child:
                FileHelper.traverse_node_for_markdown(child, mind_map, level + 1, sb)

class MindMapLayout:
    LEVEL_DISTANCE_BASE = 300.0
    MIN_NODE_WIDTH = 100.0
    MIN_NODE_HEIGHT = 60.0
    PADDING = 20.0
    TAG_HEIGHT = 30.0
    GAP = 10.0

    @staticmethod
    def layout(mind_map: MindMap, font_metrics=None):
        # Dispatch based on type
        if mind_map.layout_type == "TREE":
            MindMapLayout.layout_tree(mind_map, font_metrics)
        else:
            MindMapLayout.layout_radial(mind_map, font_metrics)

    @staticmethod
    def calculate_node_size(node: MindMapNode, font_metrics):
        # Approximate if no font metrics
        if font_metrics:
            text_width = font_metrics.horizontalAdvance(node.text)
            text_height = font_metrics.height()
        else:
            text_width = len(node.text) * 8
            text_height = 20

        tags_width = 0
        tags_height = 0
        if node.tags:
            tags_height = MindMapLayout.TAG_HEIGHT + MindMapLayout.GAP
            for tag in node.tags:
                tag_w = len(tag) * 7 + 20
                tags_width += tag_w

        # Checkbox
        if node.is_todo:
            text_width += 30 # space for checkbox

        # Images (Assuming roughly fixed height contribution for now or calculated)
        image_height = 0
        if node.images:
             # Just assume an image takes up some space
             image_height = 100

        content_width = max(text_width, tags_width)
        content_height = text_height + tags_height + image_height

        node.width = max(MindMapLayout.MIN_NODE_WIDTH, content_width + MindMapLayout.PADDING * 2)
        node.height = max(MindMapLayout.MIN_NODE_HEIGHT, content_height + MindMapLayout.PADDING * 2)

    @staticmethod
    def layout_radial(mind_map: MindMap, font_metrics):
        root = mind_map.nodes.get(mind_map.root_node_id)
        if not root:
            return

        for node in mind_map.nodes.values():
            MindMapLayout.calculate_node_size(node, font_metrics)

        if getattr(mind_map, 'layout_type', "RADIAL") == "TREE":
             MindMapLayout.layout_tree(mind_map)
        else:
             # Radial Layout
             # 1. Calculate weights
             weights = {}
             MindMapLayout.calculate_weights(root, mind_map, weights)

             # 2. Position Nodes (Radial)
             root.x = 0.0
             root.y = 0.0

             current_angle = 0.0
             total_weight = weights.get(root.id, 1)

             if not root.is_collapsed:
                 for child_id in root.children:
                     child = mind_map.nodes.get(child_id)
                     if not child:
                         continue

                     child_weight = weights.get(child_id, 1)
                     sweep = (child_weight / total_weight) * 2 * math.pi
                     mid_angle = current_angle + sweep / 2.0

                     MindMapLayout.layout_node(child, mind_map, weights, mid_angle, sweep, 1)

                     current_angle += sweep

             # 3. Collision Resolution
             MindMapLayout.resolve_collisions(mind_map)

    @staticmethod
    def layout_tree(mind_map: MindMap):
        root = mind_map.nodes.get(mind_map.root_node_id)
        if not root: return
        root.x = 0.0
        root.y = 0.0

        if root.is_collapsed or not root.children: return

        right_children = []
        left_children = []
        for i, child_id in enumerate(root.children):
            if i % 2 == 0:
                right_children.append(child_id)
            else:
                left_children.append(child_id)

        # Layout Right
        right_height = MindMapLayout.calculate_tree_height(right_children, mind_map)
        MindMapLayout.layout_tree_side(right_children, mind_map, 1, -right_height / 2.0)

        # Layout Left
        left_height = MindMapLayout.calculate_tree_height(left_children, mind_map)
        MindMapLayout.layout_tree_side(left_children, mind_map, -1, -left_height / 2.0)

    @staticmethod
    def calculate_tree_height(children: List[str], mind_map: MindMap) -> float:
        h = 0.0
        for child_id in children:
            h += MindMapLayout.calculate_subtree_height(child_id, mind_map)
        return h

    @staticmethod
    def calculate_subtree_height(node_id: str, mind_map: MindMap) -> float:
        node = mind_map.nodes.get(node_id)
        if not node: return 0.0
        if node.is_collapsed or not node.children:
            return node.height + MindMapLayout.GAP

        children_height = 0.0
        for child_id in node.children:
            children_height += MindMapLayout.calculate_subtree_height(child_id, mind_map)

        return max(node.height + MindMapLayout.GAP, children_height)

    @staticmethod
    def layout_tree_side(children: List[str], mind_map: MindMap, direction: int, start_y: float):
        current_y = start_y
        for child_id in children:
            node = mind_map.nodes.get(child_id)
            if not node: continue

            node_h = MindMapLayout.calculate_subtree_height(child_id, mind_map)
            child_y = current_y + node_h / 2.0

            root = mind_map.nodes.get(mind_map.root_node_id)
            node_x = direction * (root.width / 2.0 + 300.0/2.0 + node.width / 2.0)

            node.x = node_x
            node.y = child_y

            if not node.is_collapsed and node.children:
                MindMapLayout.layout_tree_children(node, mind_map, direction)

            current_y += node_h

    @staticmethod
    def layout_tree_children(parent: MindMapNode, mind_map: MindMap, direction: int):
        total_children_height = MindMapLayout.calculate_tree_height(parent.children, mind_map)
        current_y = parent.y - total_children_height / 2.0

        for child_id in parent.children:
            child = mind_map.nodes.get(child_id)
            if not child: continue

            child_h = MindMapLayout.calculate_subtree_height(child_id, mind_map)
            child_y = current_y + child_h / 2.0
            child_x = parent.x + direction * (parent.width / 2.0 + 100.0 + child.width / 2.0)

            child.x = child_x
            child.y = child_y

            if not child.is_collapsed and child.children:
                MindMapLayout.layout_tree_children(child, mind_map, direction)

            current_y += child_h

    @staticmethod
    def calculate_node_size(node: MindMapNode, font_metrics):
        # Approximate if no font metrics
        if font_metrics:
            text_width = font_metrics.horizontalAdvance(node.text)
            text_height = font_metrics.height()
        else:
            text_width = len(node.text) * 8
            text_height = 20

        # Images
        image_height = 0
        image_width = 0
        if node.images:
             # Assume single image for layout purposes or stack them
             # Fixed size for now to match rendering assumption
             image_height = 100.0 + MindMapLayout.GAP
             image_width = 100.0

        # Checkbox
        checkbox_height = 0
        checkbox_width = 0
        if node.is_todo:
            checkbox_height = 30.0 + MindMapLayout.GAP
            checkbox_width = 30.0

        # Tags
        tags_width = 0
        tags_height = 0
        if node.tags:
            # Simplified tag calculation
            tags_height = MindMapLayout.TAG_HEIGHT + MindMapLayout.GAP
            for tag in node.tags:
                tag_w = len(tag) * 7 + 20 # padding
                tags_width += tag_w # assuming single line for simplicity or max logic

        content_width = max(text_width, tags_width, image_width, checkbox_width)
        content_height = image_height + checkbox_height + text_height + tags_height

        node.width = max(MindMapLayout.MIN_NODE_WIDTH, content_width + MindMapLayout.PADDING * 2)
        node.height = max(MindMapLayout.MIN_NODE_HEIGHT, content_height + MindMapLayout.PADDING * 2)

    @staticmethod
    def calculate_weights(node: MindMapNode, mind_map: MindMap, weights: Dict[str, int]) -> int:
        if node.is_collapsed or not node.children:
            weights[node.id] = 1
            return 1

        s = 0
        for child_id in node.children:
            child = mind_map.nodes.get(child_id)
            if child:
                s += MindMapLayout.calculate_weights(child, mind_map, weights)

        weights[node.id] = s
        return s

    @staticmethod
    def layout_node(node: MindMapNode, mind_map: MindMap, weights: Dict[str, int], angle: float, sweep: float, depth: int):
        dist = depth * MindMapLayout.LEVEL_DISTANCE_BASE
        node.x = math.cos(angle) * dist
        node.y = math.sin(angle) * dist

        if node.is_collapsed or not node.children:
            return

        total_weight = weights.get(node.id, 1)
        current_start_angle = angle - sweep / 2.0

        for child_id in node.children:
            child = mind_map.nodes.get(child_id)
            if not child:
                continue

            child_weight = weights.get(child_id, 1)
            child_sweep = (child_weight / total_weight) * sweep
            child_mid_angle = current_start_angle + child_sweep / 2.0

            MindMapLayout.layout_node_radial(child, mind_map, weights, child_mid_angle, child_sweep, depth + 1)

            current_start_angle += child_sweep

    @staticmethod
    def layout_tree(mind_map: MindMap, font_metrics):
        root = mind_map.nodes.get(mind_map.root_node_id)
        if not root:
            return

        for node in mind_map.nodes.values():
            MindMapLayout.calculate_node_size(node, font_metrics)

        # Standard Tree Layout: Right and Left or Top Down?
        # Usually Mind Maps are Central Root, Left/Right children.
        # Let's do: Root at 0,0. Half children go Left, Half go Right.

        root.x = 0.0
        root.y = 0.0

        if root.is_collapsed or not root.children:
            return

        # Split children
        mid = len(root.children) // 2
        right_children = root.children[mid:]
        left_children = root.children[:mid]

        # Position Right
        current_y = -MindMapLayout.get_children_height(right_children, mind_map) / 2
        for child_id in right_children:
            child = mind_map.nodes.get(child_id)
            if child:
                h = MindMapLayout.get_subtree_height(child, mind_map)
                MindMapLayout.layout_node_tree(child, mind_map, 1, current_y + h/2, 1) # direction 1 (Right)
                current_y += h + MindMapLayout.GAP

        # Position Left
        current_y = -MindMapLayout.get_children_height(left_children, mind_map) / 2
        for child_id in left_children:
            child = mind_map.nodes.get(child_id)
            if child:
                h = MindMapLayout.get_subtree_height(child, mind_map)
                MindMapLayout.layout_node_tree(child, mind_map, 1, current_y + h/2, -1) # direction -1 (Left)
                current_y += h + MindMapLayout.GAP

    @staticmethod
    def layout_node_tree(node: MindMapNode, mind_map: MindMap, depth: int, y: float, direction: int):
        node.x = direction * depth * 250 # Horizontal spacing
        node.y = y

        if node.is_collapsed or not node.children:
            return

        # Layout children vertically centered on parent
        total_h = MindMapLayout.get_children_height(node.children, mind_map)
        start_y = y - total_h / 2

        for child_id in node.children:
             child = mind_map.nodes.get(child_id)
             if child:
                 h = MindMapLayout.get_subtree_height(child, mind_map)
                 MindMapLayout.layout_node_tree(child, mind_map, depth + 1, start_y + h/2, direction)
                 start_y += h + MindMapLayout.GAP

    @staticmethod
    def get_subtree_height(node: MindMapNode, mind_map: MindMap):
        if node.is_collapsed or not node.children:
            return node.height

        children_h = MindMapLayout.get_children_height(node.children, mind_map)
        return max(node.height, children_h)

    @staticmethod
    def get_children_height(children_ids: List[str], mind_map: MindMap):
        h = 0
        for cid in children_ids:
            child = mind_map.nodes.get(cid)
            if child:
                h += MindMapLayout.get_subtree_height(child, mind_map) + MindMapLayout.GAP
        return h if h > 0 else 0

    @staticmethod
    def calculate_weights(node: MindMapNode, mind_map: MindMap, weights: Dict[str, int]) -> int:
        if node.is_collapsed or not node.children:
            weights[node.id] = 1
            return 1

        s = 0
        for child_id in node.children:
            child = mind_map.nodes.get(child_id)
            if child:
                s += MindMapLayout.calculate_weights(child, mind_map, weights)

        weights[node.id] = s
        return s

    @staticmethod
    def resolve_collisions(mind_map: MindMap):
        visible_nodes = MindMapLayout.get_visible_nodes(mind_map)
        iterations = 50

        for _ in range(iterations):
            max_movement = 0.0
            for i, n1 in enumerate(visible_nodes):
                if n1.id == mind_map.root_node_id:
                    continue

                for j, n2 in enumerate(visible_nodes):
                    if i == j:
                        continue

                    dx = n1.x - n2.x
                    dy = n1.y - n2.y
                    dist = math.hypot(dx, dy)

                    r1 = max(n1.width, n1.height) / 2.0
                    r2 = max(n2.width, n2.height) / 2.0
                    min_dist = r1 + r2 + 20.0

                    if 0.001 < dist < min_dist:
                        overlap = min_dist - dist
                        push_x = (dx / dist) * overlap * 0.1
                        push_y = (dy / dist) * overlap * 0.1

                        n1.x += push_x
                        n1.y += push_y

                        max_movement = max(max_movement, math.hypot(push_x, push_y))

            if max_movement < 1.0:
                break

    @staticmethod
    def get_visible_nodes(mind_map: MindMap) -> List[MindMapNode]:
        root = mind_map.nodes.get(mind_map.root_node_id)
        if not root:
            return []

        nodes = []
        MindMapLayout.collect_visible(root, mind_map, nodes)
        return nodes

    @staticmethod
    def collect_visible(node: MindMapNode, mind_map: MindMap, lst: List[MindMapNode]):
        lst.append(node)
        if not node.is_collapsed:
            for child_id in node.children:
                child = mind_map.nodes.get(child_id)
                if child:
                    MindMapLayout.collect_visible(child, mind_map, lst)
