package com.electroradar.radar.scan;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import com.electroradar.radar.visual.RadarVisualGeometry;

/**
 * Серверная область повторяет треугольную аппроксимацию из профиля монитора.
 * Проверяется весь горизонтальный hitbox сущности по SAT и его полный вертикальный интервал;
 * центр сущности не используется как заменитель её составных частей.
 */
public record SectorRadarScanArea(AABB queryBounds, double minY, double maxY,
                                  List<RadarVisualGeometry.Triangle> footprint) implements RadarScanArea {
    private static final double EPSILON = 1.0E-9D;

    public SectorRadarScanArea {
        Objects.requireNonNull(queryBounds, "queryBounds");
        footprint = List.copyOf(footprint);
        if (!Double.isFinite(minY) || !Double.isFinite(maxY) || minY > maxY) {
            throw new IllegalArgumentException("Invalid radar scan Y range");
        }
    }

    @Override
    public boolean contains(Entity entity) {
        AABB box = entity.getBoundingBox();
        if (box.maxY < minY - EPSILON || box.minY > maxY + EPSILON) return false;
        for (RadarVisualGeometry.Triangle triangle : footprint) {
            if (intersects(triangle, box)) return true;
        }
        return false;
    }

    /** SAT с включёнными границами: касание радиальной кромки не теряет цель. */
    private static boolean intersects(RadarVisualGeometry.Triangle triangle, AABB box) {
        double[][] vertices = {
                {triangle.a().x(), triangle.a().z()},
                {triangle.b().x(), triangle.b().z()},
                {triangle.c().x(), triangle.c().z()}
        };
        double minX = box.minX;
        double maxX = box.maxX;
        double minZ = box.minZ;
        double maxZ = box.maxZ;
        if (!overlaps(project(vertices, 1.0D, 0.0D), minX, maxX, minZ, maxZ, 1.0D, 0.0D)
                || !overlaps(project(vertices, 0.0D, 1.0D), minX, maxX, minZ, maxZ, 0.0D, 1.0D)) {
            return false;
        }
        for (int index = 0; index < 3; index++) {
            double[] first = vertices[index];
            double[] second = vertices[(index + 1) % 3];
            double axisX = -(second[1] - first[1]);
            double axisZ = second[0] - first[0];
            if (Math.abs(axisX) + Math.abs(axisZ) < EPSILON) continue;
            if (!overlaps(project(vertices, axisX, axisZ), minX, maxX, minZ, maxZ, axisX, axisZ)) {
                return false;
            }
        }
        return true;
    }

    private static double[] project(double[][] points, double axisX, double axisZ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            double projection = point[0] * axisX + point[1] * axisZ;
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        return new double[]{min, max};
    }

    private static boolean overlaps(double[] triangleProjection, double minX, double maxX,
                                    double minZ, double maxZ, double axisX, double axisZ) {
        double first = minX * axisX + minZ * axisZ;
        double second = minX * axisX + maxZ * axisZ;
        double third = maxX * axisX + minZ * axisZ;
        double fourth = maxX * axisX + maxZ * axisZ;
        double rectangleMin = Math.min(Math.min(first, second), Math.min(third, fourth));
        double rectangleMax = Math.max(Math.max(first, second), Math.max(third, fourth));
        return rectangleMax >= triangleProjection[0] - EPSILON
                && triangleProjection[1] >= rectangleMin - EPSILON;
    }
}
