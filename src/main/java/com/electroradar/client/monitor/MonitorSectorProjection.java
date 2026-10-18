package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.monitor.RadarDisplayProjector;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Общий для GUI и экранного BER преобразователь геометрии обзора в координаты квадратной карты.
 *
 * <p>Сначала используется тот же {@link RadarDisplayProjector}, что и для целей, поэтому масштаб,
 * поворот панели и GUI-перетаскивание действуют одинаково. Затем линии и полигоны обрезаются по
 * нормализованному квадрату карты; рендереры получают только уже ограниченную геометрию.</p>
 */
final class MonitorSectorProjection {
    private static final double LEFT = -1.0D;
    private static final double RIGHT = 1.0D;
    private static final double BOTTOM = -1.0D;
    private static final double TOP = 1.0D;
    private static final double EPSILON = 1.0E-9D;

    private MonitorSectorProjection() {
    }

    static ProjectedCoverage project(RadarVisualProfile profile, ResourceLocation mapDimension,
                                     double mapCenterX, double mapCenterZ, Direction facing,
                                     double offsetX, double offsetZ, double mapDiameter) {
        if (!profile.dimension().equals(mapDimension)) return ProjectedCoverage.EMPTY;
        RadarVisualGeometry.Coverage source = RadarVisualGeometry.build(profile);
        ArrayList<ProjectedSegment> outline = new ArrayList<>(source.outline().size());
        for (RadarVisualGeometry.Segment segment : source.outline()) {
            projectSegment(profile, mapDimension, mapCenterX, mapCenterZ, facing,
                    offsetX, offsetZ, mapDiameter, segment).ifPresent(outline::add);
        }
        ArrayList<ProjectedSegment> separators = new ArrayList<>(source.separators().size());
        for (RadarVisualGeometry.Segment segment : source.separators()) {
            projectSegment(profile, mapDimension, mapCenterX, mapCenterZ, facing,
                    offsetX, offsetZ, mapDiameter, segment).ifPresent(separators::add);
        }
        ArrayList<ProjectedSector> sectors = new ArrayList<>(source.sectors().size());
        for (RadarVisualGeometry.Sector sector : source.sectors()) {
            ArrayList<List<MapPoint>> polygons = new ArrayList<>(sector.triangles().size());
            for (RadarVisualGeometry.Triangle triangle : sector.triangles()) {
                ArrayList<MapPoint> polygon = new ArrayList<>(3);
                for (RadarVisualGeometry.Point point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    RadarDisplayProjector.MapPoint projected = projectPoint(profile, mapDimension,
                            mapCenterX, mapCenterZ, facing, offsetX, offsetZ, mapDiameter, point);
                    if (projected == null) {
                        polygon.clear();
                        break;
                    }
                    polygon.add(new MapPoint(projected.x(), projected.y()));
                }
                List<MapPoint> clipped = clipPolygon(polygon);
                if (clipped.size() >= 3) polygons.add(clipped);
            }
            ArrayList<ProjectedSegment> boundary = new ArrayList<>(sector.boundary().size());
            for (RadarVisualGeometry.Segment segment : sector.boundary()) {
                projectSegment(profile, mapDimension, mapCenterX, mapCenterZ, facing,
                        offsetX, offsetZ, mapDiameter, segment).ifPresent(boundary::add);
            }
            if (!polygons.isEmpty()) sectors.add(new ProjectedSector(sector.index(), polygons, boundary));
        }
        return new ProjectedCoverage(outline, separators, sectors);
    }

    private static java.util.Optional<ProjectedSegment> projectSegment(
            RadarVisualProfile profile, ResourceLocation mapDimension,
            double mapCenterX, double mapCenterZ, Direction facing,
            double offsetX, double offsetZ, double mapDiameter,
            RadarVisualGeometry.Segment segment) {
        RadarDisplayProjector.MapPoint start = projectPoint(profile, mapDimension,
                mapCenterX, mapCenterZ, facing, offsetX, offsetZ, mapDiameter, segment.start());
        RadarDisplayProjector.MapPoint end = projectPoint(profile, mapDimension,
                mapCenterX, mapCenterZ, facing, offsetX, offsetZ, mapDiameter, segment.end());
        if (start == null || end == null) return java.util.Optional.empty();
        return clipSegment(new MapPoint(start.x(), start.y()), new MapPoint(end.x(), end.y()));
    }

    private static RadarDisplayProjector.MapPoint projectPoint(RadarVisualProfile profile,
                                                               ResourceLocation mapDimension,
                                                               double mapCenterX, double mapCenterZ,
                                                               Direction facing, double offsetX,
                                                               double offsetZ, double mapDiameter,
                                                               RadarVisualGeometry.Point point) {
        return RadarDisplayProjector.project(profile.dimension(), mapDimension,
                mapCenterX, mapCenterZ, facing, point.x(), point.z(),
                offsetX, offsetZ, mapDiameter);
    }

    /** Liang–Barsky сохраняет пересечение линии с краем карты вместо её полного скрытия. */
    private static java.util.Optional<ProjectedSegment> clipSegment(MapPoint first, MapPoint second) {
        double dx = second.x - first.x;
        double dy = second.y - first.y;
        double[] interval = {0.0D, 1.0D};
        if (!clipParameter(-dx, first.x - LEFT, interval)
                || !clipParameter(dx, RIGHT - first.x, interval)
                || !clipParameter(-dy, first.y - BOTTOM, interval)
                || !clipParameter(dy, TOP - first.y, interval)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ProjectedSegment(
                new MapPoint(first.x + interval[0] * dx, first.y + interval[0] * dy),
                new MapPoint(first.x + interval[1] * dx, first.y + interval[1] * dy)));
    }

    private static boolean clipParameter(double p, double q, double[] interval) {
        if (Math.abs(p) < EPSILON) return q >= 0.0D;
        double ratio = q / p;
        if (p < 0.0D) {
            if (ratio > interval[1]) return false;
            if (ratio > interval[0]) interval[0] = ratio;
        } else {
            if (ratio < interval[0]) return false;
            if (ratio < interval[1]) interval[1] = ratio;
        }
        return true;
    }

    /** Отсекает полигон последовательными полуплоскостями квадрата карты. */
    private static List<MapPoint> clipPolygon(List<MapPoint> source) {
        if (source.size() < 3) return List.of();
        List<MapPoint> result = source;
        result = clipAgainst(result, Edge.LEFT);
        result = clipAgainst(result, Edge.RIGHT);
        result = clipAgainst(result, Edge.BOTTOM);
        result = clipAgainst(result, Edge.TOP);
        return List.copyOf(result);
    }

    private static List<MapPoint> clipAgainst(List<MapPoint> input, Edge edge) {
        if (input.isEmpty()) return List.of();
        ArrayList<MapPoint> output = new ArrayList<>();
        MapPoint previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, edge);
        for (MapPoint current : input) {
            boolean currentInside = inside(current, edge);
            if (currentInside != previousInside) output.add(intersection(previous, current, edge));
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(MapPoint point, Edge edge) {
        return switch (edge) {
            case LEFT -> point.x >= LEFT - EPSILON;
            case RIGHT -> point.x <= RIGHT + EPSILON;
            case BOTTOM -> point.y >= BOTTOM - EPSILON;
            case TOP -> point.y <= TOP + EPSILON;
        };
    }

    private static MapPoint intersection(MapPoint from, MapPoint to, Edge edge) {
        if (edge == Edge.LEFT || edge == Edge.RIGHT) {
            double x = edge == Edge.LEFT ? LEFT : RIGHT;
            double denominator = to.x - from.x;
            double fraction = Math.abs(denominator) < EPSILON ? 0.0D : (x - from.x) / denominator;
            return new MapPoint(x, from.y + (to.y - from.y) * fraction);
        }
        double y = edge == Edge.BOTTOM ? BOTTOM : TOP;
        double denominator = to.y - from.y;
        double fraction = Math.abs(denominator) < EPSILON ? 0.0D : (y - from.y) / denominator;
        return new MapPoint(from.x + (to.x - from.x) * fraction, y);
    }

    enum Edge { LEFT, RIGHT, BOTTOM, TOP }

    record MapPoint(double x, double y) {
    }

    record ProjectedSegment(MapPoint start, MapPoint end) {
    }

    record ProjectedSector(int index, List<List<MapPoint>> polygons,
                           List<ProjectedSegment> boundary) {
        ProjectedSector {
            polygons = polygons.stream().map(List::copyOf).toList();
            boundary = List.copyOf(boundary);
        }
    }

    record ProjectedCoverage(List<ProjectedSegment> outline,
                             List<ProjectedSegment> separators,
                             List<ProjectedSector> sectors) {
        private static final ProjectedCoverage EMPTY = new ProjectedCoverage(List.of(), List.of(), List.of());

        ProjectedCoverage {
            outline = List.copyOf(outline);
            separators = List.copyOf(separators);
            sectors = List.copyOf(sectors);
        }
    }
}
