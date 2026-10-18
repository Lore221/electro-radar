package com.electroradar.client.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Отрисовка общей секторной геометрии на центральной квадратной карте GUI. */
final class MonitorSectorGuiRenderer {
    private MonitorSectorGuiRenderer() {
    }

    /**
     * Рисует заливку, внешний контур и разделители поверх фона, до меток и рамок.
     * Координаты карты уже учитывают текущие масштаб, направление экрана и панорамирование GUI.
     */
    static void draw(GuiGraphics graphics, MonitorClientState.View view,
                     ResourceLocation mapDimension, double mapCenterX, double mapCenterZ,
                     Direction facing, double offsetX, double offsetZ, double mapDiameter,
                     int contentX, int contentY, int contentSize) {
        if (view == null) return;
        MonitorSnapshotData snapshot = view.snapshot();
        if (snapshot.visualProfiles().isEmpty()) return;
        Map<RadarVisualProfile.Key, Integer> activeByProfile = new HashMap<>();
        for (MonitorSectorAnimationState.ActiveSector active : view.activeSectors()) {
            activeByProfile.put(active.profile().key(), active.sectorIndex());
        }

        double centerX = contentX + contentSize * 0.5D;
        double centerY = contentY + contentSize * 0.5D;
        double radius = contentSize * 0.5D;
        for (RadarVisualProfile profile : snapshot.visualProfiles()) {
            MonitorSectorProjection.ProjectedCoverage coverage = MonitorSectorProjection.project(
                    profile, mapDimension, mapCenterX, mapCenterZ, facing,
                    offsetX, offsetZ, mapDiameter);
            Integer activeIndex = activeByProfile.get(profile.key());
            if (activeIndex != null) {
                for (MonitorSectorProjection.ProjectedSector sector : coverage.sectors()) {
                    if (sector.index() == activeIndex) {
                        for (List<MonitorSectorProjection.MapPoint> polygon : sector.polygons()) {
                            fillPolygon(graphics, polygon, centerX, centerY, radius,
                                    MonitorSectorPalette.ACTIVE_FILL_ARGB);
                        }
                        break;
                    }
                }
            }

            for (MonitorSectorProjection.ProjectedSegment line : coverage.outline()) {
                drawLine(graphics, line, centerX, centerY, radius,
                        MonitorSectorPalette.BASE_LINE_ARGB, 1);
            }
            for (MonitorSectorProjection.ProjectedSegment line : coverage.separators()) {
                drawLine(graphics, line, centerX, centerY, radius,
                        MonitorSectorPalette.BASE_LINE_ARGB, 1);
            }
            if (activeIndex != null) {
                for (MonitorSectorProjection.ProjectedSector sector : coverage.sectors()) {
                    if (sector.index() == activeIndex) {
                        for (MonitorSectorProjection.ProjectedSegment edge : sector.boundary()) {
                            drawLine(graphics, edge, centerX, centerY, radius,
                                    MonitorSectorPalette.ACTIVE_EDGE_ARGB, 2);
                        }
                        break;
                    }
                }
            }
        }
    }

    /** Scanline-заливка полигонов оставляет расчёт кадра на клиенте и не требует новой текстуры. */
    private static void fillPolygon(GuiGraphics graphics, List<MonitorSectorProjection.MapPoint> polygon,
                                   double centerX, double centerY, double radius, int argb) {
        if (polygon.size() < 3) return;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (MonitorSectorProjection.MapPoint point : polygon) {
            double y = centerY - point.y() * radius;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int firstY = Math.max((int) Math.floor(minY), 0);
        int lastY = Math.min((int) Math.ceil(maxY), graphics.guiHeight());
        for (int y = firstY; y < lastY; y++) {
            double scanY = y + 0.5D;
            double[] intersections = new double[polygon.size()];
            int intersectionCount = 0;
            for (int i = 0; i < polygon.size(); i++) {
                MonitorSectorProjection.MapPoint first = polygon.get(i);
                MonitorSectorProjection.MapPoint second = polygon.get((i + 1) % polygon.size());
                double firstX = centerX + first.x() * radius;
                double firstScreenY = centerY - first.y() * radius;
                double secondX = centerX + second.x() * radius;
                double secondScreenY = centerY - second.y() * radius;
                if ((firstScreenY <= scanY && secondScreenY > scanY)
                        || (secondScreenY <= scanY && firstScreenY > scanY)) {
                    double fraction = (scanY - firstScreenY) / (secondScreenY - firstScreenY);
                    intersections[intersectionCount++] = firstX + (secondX - firstX) * fraction;
                }
            }
            java.util.Arrays.sort(intersections, 0, intersectionCount);
            for (int i = 0; i + 1 < intersectionCount; i += 2) {
                int left = (int) Math.ceil(intersections[i]);
                int right = (int) Math.floor(intersections[i + 1]);
                if (right >= left) graphics.fill(left, y, right + 1, y + 1, argb);
            }
        }
    }

    /** Рисует каждый уже обрезанный отрезок целиком, включая точку его пересечения с краем карты. */
    private static void drawLine(GuiGraphics graphics,
                                 MonitorSectorProjection.ProjectedSegment line,
                                 double centerX, double centerY, double radius,
                                 int argb, int thickness) {
        int x0 = (int) Math.round(centerX + line.start().x() * radius);
        int y0 = (int) Math.round(centerY - line.start().y() * radius);
        int x1 = (int) Math.round(centerX + line.end().x() * radius);
        int y1 = (int) Math.round(centerY - line.end().y() * radius);
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            int half = thickness / 2;
            graphics.fill(x0 - half, y0 - half, x0 - half + thickness,
                    y0 - half + thickness, argb);
            if (x0 == x1 && y0 == y1) break;
            int twiceError = 2 * error;
            if (twiceError >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twiceError <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }
}
