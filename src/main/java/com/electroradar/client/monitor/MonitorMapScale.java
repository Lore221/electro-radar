package com.electroradar.client.monitor;

import java.util.List;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Общий автоматический масштаб GUI и экрана в мире; центр карты остаётся у монитора. */
final class MonitorMapScale {
    static final double FALLBACK_DIAMETER_BLOCKS = 200.0D;
    private static final double MIN_DIAMETER_BLOCKS = 100.0D;
    private static final double ROUNDING_STEP_BLOCKS = 100.0D;
    private static final double FIT_MARGIN = 1.05D;

    private MonitorMapScale() {
    }

    /**
     * Берёт максимальный требуемый охват среди подключённых профилей. Учитывает положение
     * радара относительно экрана и все примитивы, в том числе покрытия без outline.
     * Для горизонтальных FACING поворот осей не меняет нужный размер квадратной карты.
     */
    static double diameter(MonitorSnapshotData snapshot, double centerX, double centerZ) {
        if (snapshot == null) return FALLBACK_DIAMETER_BLOCKS;
        double radius = 0.0D;
        for (RadarVisualProfile profile : snapshot.visualProfiles()) {
            RadarVisualGeometry.Coverage coverage = profile.coverage();
            radius = Math.max(radius, extent(coverage.outline(), centerX, centerZ));
            radius = Math.max(radius, extent(coverage.separators(), centerX, centerZ));
            for (RadarVisualGeometry.Sector sector : coverage.sectors()) {
                radius = Math.max(radius, extent(sector.boundary(), centerX, centerZ));
                for (RadarVisualGeometry.Triangle triangle : sector.triangles()) {
                    radius = Math.max(radius, extent(triangle.a(), centerX, centerZ));
                    radius = Math.max(radius, extent(triangle.b(), centerX, centerZ));
                    radius = Math.max(radius, extent(triangle.c(), centerX, centerZ));
                }
            }
        }
        if (radius == 0.0D) return FALLBACK_DIAMETER_BLOCKS;
        double diameter = radius * 2.0D * FIT_MARGIN;
        return Math.max(MIN_DIAMETER_BLOCKS,
                Math.ceil(diameter / ROUNDING_STEP_BLOCKS) * ROUNDING_STEP_BLOCKS);
    }

    private static double extent(List<RadarVisualGeometry.Segment> lines, double centerX, double centerZ) {
        double radius = 0.0D;
        for (RadarVisualGeometry.Segment line : lines) {
            radius = Math.max(radius, extent(line.start(), centerX, centerZ));
            radius = Math.max(radius, extent(line.end(), centerX, centerZ));
        }
        return radius;
    }

    private static double extent(RadarVisualGeometry.Point point, double centerX, double centerZ) {
        return Math.max(Math.abs(point.x() - centerX), Math.abs(point.z() - centerZ));
    }
}
