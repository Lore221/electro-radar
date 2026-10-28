package com.electroradar.client.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Общий выбор слоёв и триангуляция отсечённой геометрии для GUI и мировой поверхности. */
final class MonitorSectorMesh {
    private MonitorSectorMesh() { }

    enum LineStyle { BASE, ACTIVE }

    record Style(int baseLine, int activeFill, int activeEdge, int flashFill, int flashEdge) { }

    interface Sink {
        void triangle(MonitorSectorProjection.MapPoint first, MonitorSectorProjection.MapPoint second,
                      MonitorSectorProjection.MapPoint third, int argb);

        void line(MonitorSectorProjection.ProjectedSegment segment, int argb, LineStyle style);
    }

    static Map<RadarVisualProfile.Key, MonitorSectorAnimationState.ProfileFrame> framesByProfile(
            MonitorClientState.View view) {
        Map<RadarVisualProfile.Key, MonitorSectorAnimationState.ProfileFrame> frames = new HashMap<>();
        for (MonitorSectorAnimationState.ProfileFrame frame : view.visualFrames()) {
            frames.put(frame.profile().key(), frame);
        }
        return frames;
    }

    static void emit(MonitorSectorProjection.ProjectedCoverage coverage, RadarVisualAnimation animation,
                     MonitorSectorAnimationState.ProfileFrame frame, Style colors, Sink sink) {
        boolean flash = animation == RadarVisualAnimation.FULL_FLASH;
        float strength = frame == null ? 0.0F : frame.flashStrength();
        int fill = flash ? MonitorSectorPalette.scaleAlpha(colors.flashFill(), strength) : colors.activeFill();
        for (MonitorSectorProjection.ProjectedSector sector : coverage.sectors()) {
            if ((fill >>> 24) == 0 || !flash && !active(frame, sector.index())) continue;
            for (List<MonitorSectorProjection.MapPoint> polygon : sector.polygons()) {
                fillPolygon(polygon, fill, sink);
            }
        }
        for (MonitorSectorProjection.ProjectedSegment line : coverage.outline()) {
            sink.line(line, colors.baseLine(), LineStyle.BASE);
        }
        if (animation == RadarVisualAnimation.SECTOR_SWEEP) {
            for (MonitorSectorProjection.ProjectedSegment line : coverage.separators()) {
                sink.line(line, colors.baseLine(), LineStyle.BASE);
            }
            for (MonitorSectorProjection.ProjectedSector sector : coverage.sectors()) {
                if (!active(frame, sector.index())) continue;
                for (MonitorSectorProjection.ProjectedSegment line : sector.boundary()) {
                    sink.line(line, colors.activeEdge(), LineStyle.ACTIVE);
                }
            }
        } else if (strength > 0.0F) {
            int edge = MonitorSectorPalette.scaleAlpha(colors.flashEdge(), strength);
            for (MonitorSectorProjection.ProjectedSegment line : coverage.outline()) {
                sink.line(line, edge, LineStyle.ACTIVE);
            }
        }
    }

    private static boolean active(MonitorSectorAnimationState.ProfileFrame frame, int sectorIndex) {
        return frame != null && frame.activeSectorIndices().contains(sectorIndex);
    }

    /** Каждый полигон — пересечение исходного треугольника с квадратом, то есть выпуклый. */
    private static void fillPolygon(List<MonitorSectorProjection.MapPoint> polygon, int argb, Sink sink) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            sink.triangle(polygon.getFirst(), polygon.get(i), polygon.get(i + 1), argb);
        }
    }
}
