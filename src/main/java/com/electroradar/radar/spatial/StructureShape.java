package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Optional;
import com.electroradar.radar.scan.ConvexRadarIntersection;
import com.electroradar.radar.scan.RadarScanArea;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Неизменяемая локальная форма с иерархией границ. Порядок точных проверок блоков сохранён. */
public final class StructureShape {
    private static final int LEAF_SIZE = 16;
    private final List<AABB> boxes;
    private final Node root;

    public StructureShape(List<AABB> boxes) {
        this.boxes = List.copyOf(boxes);
        root = boxes.isEmpty() ? null : build(0, boxes.size());
    }

    public List<AABB> boxes() { return boxes; }

    /** Общие локальные габариты для приближённого изображения, не точной физики. */
    Optional<AABB> localBounds() { return root == null ? Optional.empty() : Optional.of(root.bounds); }

    public boolean intersects(RadarScanArea area, SpatialTransform frame) {
        return root != null && !area.isEmpty() && intersects(root, area.queryBounds().inflate(1.0E-7), area, frame);
    }

    public Optional<Vec3> scanPoint(RadarScanArea area, SpatialTransform frame) {
        if (root == null || area.isEmpty()) return Optional.empty();
        return Optional.ofNullable(scanPoint(root, area.queryBounds().inflate(1.0E-7), area, frame));
    }

    /** Точные мировые границы объединения блоков; поворот общего local AABB дал бы завышение. */
    public Optional<AABB> bounds(SpatialTransform frame) {
        return frame.bounds(boxes);
    }

    private Node build(int start, int end) {
        if (end - start <= LEAF_SIZE) {
            AABB bounds = boxes.get(start);
            for (int i=start+1;i<end;i++) bounds = bounds.minmax(boxes.get(i));
            return new Node(start, end, bounds, null, null);
        }
        int middle = (start + end) >>> 1;
        Node left = build(start, middle), right = build(middle, end);
        return new Node(start, end, left.bounds.minmax(right.bounds), left, right);
    }

    private boolean intersects(Node node, AABB query, RadarScanArea area, SpatialTransform frame) {
        if (!query.intersects(frame.bounds(node.bounds))) return false;
        if (node.left != null) return intersects(node.left, query, area, frame) || intersects(node.right, query, area, frame);
        for (int i=node.start;i<node.end;i++) {
            AABB box = boxes.get(i);
            if (query.intersects(frame.bounds(box)) && ConvexRadarIntersection.intersects(area, frame.corners(box))) return true;
        }
        return false;
    }

    private Vec3 scanPoint(Node node, AABB query, RadarScanArea area, SpatialTransform frame) {
        if (!query.intersects(frame.bounds(node.bounds))) return null;
        if (node.left != null) {
            Vec3 first = scanPoint(node.left, query, area, frame);
            return first != null ? first : scanPoint(node.right, query, area, frame);
        }
        for (int i=node.start;i<node.end;i++) {
            AABB box = boxes.get(i);
            if (!query.intersects(frame.bounds(box))) continue;
            List<Vec3> corners = frame.corners(box);
            if (!ConvexRadarIntersection.intersects(area, corners)) continue;
            Optional<Vec3> point = ConvexRadarIntersection.intersectionPoint(area, corners);
            if (point.isPresent()) return point.orElseThrow();
        }
        return null;
    }

    private record Node(int start, int end, AABB bounds, Node left, Node right) { }
}
