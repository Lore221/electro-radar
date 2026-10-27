package com.electroradar.radar.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import com.electroradar.radar.visual.RadarVisualGeometry;

/** Неизменяемая область: границы и проекции SAT готовятся один раз вместе с планом радара. */
public final class SectorRadarScanArea implements RadarScanArea {
    private static final double EPSILON = 1.0E-9D;
    private final AABB queryBounds;
    private final double minY;
    private final double maxY;
    private final List<RadarVisualGeometry.Triangle> footprint;
    private final List<List<Axis>> axes;

    public SectorRadarScanArea(AABB queryBounds, double minY, double maxY,
                               List<RadarVisualGeometry.Triangle> footprint) {
        this.queryBounds = Objects.requireNonNull(queryBounds, "queryBounds");
        if (!Double.isFinite(minY) || !Double.isFinite(maxY) || minY > maxY) {
            throw new IllegalArgumentException("Invalid radar scan Y range");
        }
        this.minY = minY;
        this.maxY = maxY;
        this.footprint = List.copyOf(footprint);
        this.axes = this.footprint.stream().map(SectorRadarScanArea::prepare).toList();
    }

    @Override public AABB queryBounds() { return queryBounds; }
    @Override public boolean isEmpty() { return footprint.isEmpty(); }
    public double minY() { return minY; }
    public double maxY() { return maxY; }
    public List<RadarVisualGeometry.Triangle> footprint() { return footprint; }

    @Override
    public boolean contains(Entity entity) {
        AABB box = entity.getBoundingBox();
        if (box.maxY < minY - EPSILON || box.minY > maxY + EPSILON) return false;
        for (List<Axis> triangleAxes : axes) {
            boolean intersects = true;
            for (Axis axis : triangleAxes) {
                if (!axis.overlaps(box)) {
                    intersects = false;
                    break;
                }
            }
            if (intersects) return true;
        }
        return false;
    }

    /** Сохраняем прежние ненормированные оси и допуск: касание hitbox остаётся включительным. */
    private static List<Axis> prepare(RadarVisualGeometry.Triangle triangle) {
        List<RadarVisualGeometry.Point> vertices = List.of(triangle.a(), triangle.b(), triangle.c());
        ArrayList<Axis> result = new ArrayList<>(5);
        result.add(axis(vertices, 1.0D, 0.0D));
        result.add(axis(vertices, 0.0D, 1.0D));
        for (int i = 0; i < 3; i++) {
            RadarVisualGeometry.Point first = vertices.get(i);
            RadarVisualGeometry.Point second = vertices.get((i + 1) % 3);
            double x = -(second.z() - first.z());
            double z = second.x() - first.x();
            if (Math.abs(x) + Math.abs(z) >= EPSILON) result.add(axis(vertices, x, z));
        }
        return List.copyOf(result);
    }

    private static Axis axis(List<RadarVisualGeometry.Point> points, double x, double z) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (RadarVisualGeometry.Point point : points) {
            double value = point.x() * x + point.z() * z;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new Axis(x, z, min, max);
    }

    private record Axis(double x, double z, double min, double max) {
        boolean overlaps(AABB box) {
            double first = box.minX * x + box.minZ * z;
            double second = box.minX * x + box.maxZ * z;
            double third = box.maxX * x + box.minZ * z;
            double fourth = box.maxX * x + box.maxZ * z;
            double rectangleMin = Math.min(Math.min(first, second), Math.min(third, fourth));
            double rectangleMax = Math.max(Math.max(first, second), Math.max(third, fourth));
            return rectangleMax >= min - EPSILON && max >= rectangleMin - EPSILON;
        }
    }
}
