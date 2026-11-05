package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.List;

import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.visual.RadarVisualGeometry.Segment;
import com.electroradar.radar.visual.RadarVisualGeometry.Triangle;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Клиентские лучи разрезают исходный веер, сохраняя каждую хорду серверного контура. */
final class MonitorSweepGeometry {
    private static final double EPSILON = 1.0E-10D;

    private MonitorSweepGeometry() { }

    static RadarVisualProfile partition(RadarVisualProfile source, int count) {
        List<Triangle> original = source.coverage().sectors().stream()
                .flatMap(sector -> sector.triangles().stream()).toList();
        Point center = new Point(source.centerX(), source.centerZ());
        double[] widths = new double[original.size()];
        double sweep = 0;
        for (int i = 0; i < original.size(); i++) {
            Triangle triangle = original.get(i);
            // Общие профили другой формы остаются в исходном виде. Стационарные
            // радары передают упорядоченный веер с одним центром.
            if (Math.hypot(triangle.a().x() - center.x(), triangle.a().z() - center.z()) > EPSILON) return source;
            double bx = triangle.b().x() - center.x(), bz = triangle.b().z() - center.z();
            double cx = triangle.c().x() - center.x(), cz = triangle.c().z() - center.z();
            widths[i] = Math.atan2(bx * cz - bz * cx, bx * cx + bz * cz);
            if (widths[i] <= 0) return source;
            sweep += widths[i];
        }
        List<RadarVisualGeometry.Sector> sectors = new ArrayList<>(count);
        List<Segment> separators = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double start = sweep * index / count, end = sweep * (index + 1) / count;
            double offset = 0;
            List<Triangle> triangles = new ArrayList<>();
            List<Point> edge = new ArrayList<>();
            for (int i = 0; i < original.size(); i++) {
                double from = Math.max(start, offset), to = Math.min(end, offset + widths[i]);
                if (to - from > EPSILON) {
                    Triangle triangle = original.get(i);
                    Point first = boundary(center, triangle, from - offset, widths[i]);
                    Point last = boundary(center, triangle, to - offset, widths[i]);
                    triangles.add(new Triangle(center, first, last));
                    if (edge.isEmpty()) edge.add(first);
                    edge.add(last);
                }
                offset += widths[i];
            }
            List<Segment> boundary = new ArrayList<>();
            if (!edge.isEmpty()) {
                boundary.add(new Segment(center, edge.getFirst()));
                for (int i = 1; i < edge.size(); i++) boundary.add(new Segment(edge.get(i - 1), edge.get(i)));
                boundary.add(new Segment(edge.getLast(), center));
                if (count > 1 && (index > 0 || Math.abs(sweep - RadarVisualProfile.FULL_TURN) < EPSILON)) {
                    separators.add(new Segment(center, edge.getFirst()));
                }
            }
            sectors.add(new RadarVisualGeometry.Sector(index, triangles,
                    count == 1 ? source.coverage().outline() : boundary));
        }
        return new RadarVisualProfile(source.dimension(), source.radarPosition(), source.centerX(),
                source.centerY(), source.centerZ(), new RadarVisualGeometry.Coverage(
                        source.coverage().outline(), separators, sectors), source.animation(),
                source.sectorOrderVariant(), source.inactiveSectorFadeStyle());
    }

    /** Пересечение луча с хордой; интерполяция по окружности изменила бы область радара. */
    private static Point boundary(Point center, Triangle triangle, double angle, double width) {
        if (angle <= EPSILON) return triangle.b();
        if (angle >= width - EPSILON) return triangle.c();
        double bx = triangle.b().x() - center.x(), bz = triangle.b().z() - center.z();
        double rayX = bx * Math.cos(angle) - bz * Math.sin(angle);
        double rayZ = bx * Math.sin(angle) + bz * Math.cos(angle);
        double edgeX = triangle.c().x() - triangle.b().x();
        double edgeZ = triangle.c().z() - triangle.b().z();
        double fraction = Math.clamp(-(bx * rayZ - bz * rayX) / (edgeX * rayZ - edgeZ * rayX), 0.0D, 1.0D);
        return new Point(triangle.b().x() + fraction * edgeX, triangle.b().z() + fraction * edgeZ);
    }
}
