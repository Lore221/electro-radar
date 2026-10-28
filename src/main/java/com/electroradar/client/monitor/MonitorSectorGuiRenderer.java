package com.electroradar.client.monitor;

import java.util.Map;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
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
        Map<RadarVisualProfile.Key, MonitorSectorAnimationState.ProfileFrame> frameByProfile =
                MonitorSectorMesh.framesByProfile(view);

        double centerX = contentX + contentSize * 0.5D;
        double centerY = contentY + contentSize * 0.5D;
        double radius = contentSize * 0.5D;
        // GuiGraphics.fill немедленно сбрасывает буфер. Геометрия карты целиком использует
        // один GUI-буфер; сброс до/после сохраняет порядок относительно фона и меток.
        graphics.flush();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = graphics.pose().last().pose();
        MonitorSectorMesh.Style colors = MonitorSectorPalette.guiStyle();
        MonitorSectorMesh.Sink sink = new MonitorSectorMesh.Sink() {
            @Override
            public void triangle(MonitorSectorProjection.MapPoint first, MonitorSectorProjection.MapPoint second,
                                 MonitorSectorProjection.MapPoint third, int argb) {
                drawTriangle(vertices, pose, first, second, third, centerX, centerY, radius, argb);
            }

            @Override
            public void line(MonitorSectorProjection.ProjectedSegment line, int argb, MonitorSectorMesh.LineStyle style) {
                drawLine(vertices, pose, line, centerX, centerY, radius, argb,
                        style == MonitorSectorMesh.LineStyle.ACTIVE ? 2 : 1);
            }
        };
        try {
            for (RadarVisualProfile profile : snapshot.visualProfiles()) {
                MonitorSectorProjection.ProjectedCoverage coverage = MonitorSectorProjection.project(
                        profile, mapDimension, mapCenterX, mapCenterZ, facing,
                        offsetX, offsetZ, mapDiameter);
                MonitorSectorMesh.emit(coverage, profile.animation(), frameByProfile.get(profile.key()), colors, sink);
            }
        } finally {
            // Отсечение остаётся включённым вызывающим экраном до завершения этого пакета.
            graphics.flush();
        }
    }

    /** GUI-тип использует QUADS и culling; повтор вершины задаёт один треугольник. */
    private static void drawTriangle(VertexConsumer vertices, Matrix4f pose,
                                     MonitorSectorProjection.MapPoint first,
                                     MonitorSectorProjection.MapPoint second,
                                     MonitorSectorProjection.MapPoint third,
                                     double centerX, double centerY, double radius, int argb) {
        double ax = centerX + first.x() * radius;
        double ay = centerY - first.y() * radius;
        double bx = centerX + second.x() * radius;
        double by = centerY - second.y() * radius;
        double cx = centerX + third.x() * radius;
        double cy = centerY - third.y() * radius;
        double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross == 0.0D) return;
        vertex(vertices, pose, ax, ay, argb);
        if (cross < 0.0D) {
            vertex(vertices, pose, bx, by, argb);
            vertex(vertices, pose, cx, cy, argb);
            vertex(vertices, pose, cx, cy, argb);
        } else {
            vertex(vertices, pose, cx, cy, argb);
            vertex(vertices, pose, bx, by, argb);
            vertex(vertices, pose, bx, by, argb);
        }
    }

    /** Один квад на отрезок; толщина и квадратные концы заданы в GUI-пикселях. */
    private static void drawLine(VertexConsumer vertices, Matrix4f pose,
                                 MonitorSectorProjection.ProjectedSegment line,
                                 double centerX, double centerY, double radius,
                                 int argb, int thickness) {
        if ((argb >>> 24) == 0) return;
        double pixelCenter = (thickness & 1) == 0 ? 0.0D : 0.5D;
        double x0 = Math.round(centerX + line.start().x() * radius) + pixelCenter;
        double y0 = Math.round(centerY - line.start().y() * radius) + pixelCenter;
        double x1 = Math.round(centerX + line.end().x() * radius) + pixelCenter;
        double y1 = Math.round(centerY - line.end().y() * radius) + pixelCenter;
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.hypot(dx, dy);
        double half = thickness * 0.5D;
        if (length == 0.0D) {
            vertex(vertices, pose, x0 + half, y0 + half, argb);
            vertex(vertices, pose, x0 + half, y0 - half, argb);
            vertex(vertices, pose, x0 - half, y0 - half, argb);
            vertex(vertices, pose, x0 - half, y0 + half, argb);
            return;
        }
        double alongX = dx / length * half;
        double alongY = dy / length * half;
        double normalX = -alongY;
        double normalY = alongX;
        vertex(vertices, pose, x0 - alongX + normalX, y0 - alongY + normalY, argb);
        vertex(vertices, pose, x1 + alongX + normalX, y1 + alongY + normalY, argb);
        vertex(vertices, pose, x1 + alongX - normalX, y1 + alongY - normalY, argb);
        vertex(vertices, pose, x0 - alongX - normalX, y0 - alongY - normalY, argb);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, double x, double y, int argb) {
        vertices.addVertex(pose, (float) x, (float) y, 0.0F).setColor(argb);
    }
}
