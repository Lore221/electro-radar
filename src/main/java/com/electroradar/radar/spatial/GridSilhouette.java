package com.electroradar.radar.spatial;

import java.util.*;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.visual.RadarVisualGeometry.Segment;
import net.minecraft.world.phys.Vec3;

/** Exact union of occupied XZ cells, compressed into non-overlapping rectangles and boundary runs. */
public final class GridSilhouette {
    private GridSilhouette() { }
    public static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    public record Result(Silhouette silhouette, Vec3 center) { }
    private record Run(int left, int right) { }
    private record Rect(int left, int right, int bottom, int top) { }
    private record Edge(boolean horizontal, int constant) { }

    public static Result build(Set<Long> cells, Vec3 origin, Vec3 forward, double scaleX, double scaleZ) {
        if (cells.isEmpty()) return new Result(Silhouette.EMPTY, origin);
        TreeMap<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        Map<Edge, TreeSet<Integer>> edges = new HashMap<>();
        for (long cell : cells) {
            int x = (int)(cell >> 32), z = (int)cell;
            rows.computeIfAbsent(z, ignored -> new TreeSet<>()).add(x);
            if (!cells.contains(key(x, z - 1))) edges.computeIfAbsent(new Edge(true, z), ignored -> new TreeSet<>()).add(x);
            if (!cells.contains(key(x, z + 1))) edges.computeIfAbsent(new Edge(true, z + 1), ignored -> new TreeSet<>()).add(x);
            if (!cells.contains(key(x - 1, z))) edges.computeIfAbsent(new Edge(false, x), ignored -> new TreeSet<>()).add(z);
            if (!cells.contains(key(x + 1, z))) edges.computeIfAbsent(new Edge(false, x + 1), ignored -> new TreeSet<>()).add(z);
        }
        List<Rect> rectangles = new ArrayList<>();
        Map<Run, Rect> active = new HashMap<>();
        int previousZ = Integer.MIN_VALUE;
        for (var row : rows.entrySet()) {
            if (row.getKey() != previousZ + 1) { rectangles.addAll(active.values()); active.clear(); }
            Map<Run, Rect> next = new HashMap<>();
            for (Run run : runs(row.getValue())) {
                Rect old = active.remove(run);
                next.put(run, new Rect(run.left(), run.right(), old == null ? row.getKey() : old.bottom(), row.getKey() + 1));
            }
            rectangles.addAll(active.values()); active = next; previousZ = row.getKey();
        }
        rectangles.addAll(active.values());
        // Determinism matters: identical geometry must not change contentVersion through hash iteration order.
        rectangles.sort(Comparator.comparingInt(Rect::bottom).thenComparingInt(Rect::left));
        List<Silhouette.Quad> fills = new ArrayList<>();
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (Rect r : rectangles) {
            var q = new Silhouette.Quad(point(r.left(), r.bottom(), origin, forward, scaleX, scaleZ),
                    point(r.right(), r.bottom(), origin, forward, scaleX, scaleZ),
                    point(r.right(), r.top(), origin, forward, scaleX, scaleZ),
                    point(r.left(), r.top(), origin, forward, scaleX, scaleZ));
            fills.add(q);
            for (Point v : List.of(q.a(), q.b(), q.c(), q.d())) {
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
        }
        List<Segment> outline = new ArrayList<>();
        List<Edge> orderedEdges = new ArrayList<>(edges.keySet());
        orderedEdges.sort(Comparator.comparing(Edge::horizontal).thenComparingInt(Edge::constant));
        for (Edge edge : orderedEdges) for (Run run : runs(edges.get(edge))) {
            outline.add(new Segment(point(edge.horizontal() ? run.left() : edge.constant(),
                    edge.horizontal() ? edge.constant() : run.left(), origin, forward, scaleX, scaleZ),
                    point(edge.horizontal() ? run.right() : edge.constant(),
                    edge.horizontal() ? edge.constant() : run.right(), origin, forward, scaleX, scaleZ)));
        }
        Vec3 center = new Vec3((minX + maxX) * 0.5D, origin.y, (minZ + maxZ) * 0.5D);
        if (fills.size() + outline.size() > Silhouette.MAX_PRIMITIVES) {
            return new Result(new Silhouette(List.of(), List.of(), true), center);
        }
        return new Result(new Silhouette(fills, outline, false), center);
    }

    private static List<Run> runs(SortedSet<Integer> values) {
        List<Run> result = new ArrayList<>();
        Integer start = null; int end = 0;
        for (int value : values) {
            if (start == null) { start = value; end = value + 1; }
            else if (value == end) end++;
            else { result.add(new Run(start, end)); start = value; end = value + 1; }
        }
        if (start != null) result.add(new Run(start, end));
        return result;
    }
    private static Point point(int x, int z, Vec3 origin, Vec3 forward, double sx, double sz) {
        double cosine = -forward.z, sine = forward.x;
        return new Point(origin.x + x * sx * cosine - z * sz * sine,
                origin.z + x * sx * sine + z * sz * cosine);
    }
}
