package com.electroradar.client.monitor;

import java.util.*;
import com.electroradar.radar.monitor.*;
import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Both surfaces draw only the measured primitives, never query a live client structure. */
final class MonitorSilhouetteRenderer {
    private MonitorSilhouetteRenderer() { }
    private interface Sink {
        void triangle(MonitorSectorProjection.MapPoint a, MonitorSectorProjection.MapPoint b,
                      MonitorSectorProjection.MapPoint c, int color);
        void line(MonitorSectorProjection.ProjectedSegment line, int color);
    }
    static void gui(GuiGraphics graphics, MonitorClientState.View view, double cx, double cz,
            MapFacing facing, double ox, double oz, double diameter, int left, int top, int size, float partialTick) {
        graphics.flush();
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        var pose = graphics.pose().last().pose();
        double x = left + size * 0.5D, y = top + size * 0.5D, radius = size * 0.5D;
        emit(view, cx, cz, facing, ox, oz, diameter, partialTick, new Sink() {
            public void triangle(MonitorSectorProjection.MapPoint a, MonitorSectorProjection.MapPoint b,
                    MonitorSectorProjection.MapPoint c, int color) {
                MonitorSectorGuiRenderer.drawTriangle(vertices, pose, a, b, c, x, y, radius, color);
            }
            public void line(MonitorSectorProjection.ProjectedSegment line, int color) {
                MonitorSectorGuiRenderer.drawLine(vertices, pose, line, x, y, radius, color, 1);
            }
        });
        graphics.flush();
    }
    static void world(RadarDisplayBlockEntity display, PoseStack pose, MultiBufferSource buffers,
            Direction surface, BlockPos origin, MonitorClientState.View view, double cx, double cz,
            MapFacing facing, double diameter, double u, double v, double left, double bottom,
            double side, double radius, int overlay, float partialTick) {
        var vertices = buffers.getBuffer(RenderType.entityTranslucentEmissive(MonitorBlipSprite.TEXTURE));
        emit(view, cx, cz, facing, 0, 0, diameter, partialTick, new Sink() {
            public void triangle(MonitorSectorProjection.MapPoint a, MonitorSectorProjection.MapPoint b,
                    MonitorSectorProjection.MapPoint c, int color) {
                MonitorSectorWorldRenderer.drawTriangle(display, pose, vertices, surface, origin, a, b, c,
                        u, v, radius, left, bottom, side, color, overlay, 0.00745F);
            }
            public void line(MonitorSectorProjection.ProjectedSegment line, int color) {
                MonitorSectorWorldRenderer.drawLine(display, pose, vertices, surface, origin, line,
                        u, v, radius, left, bottom, side, color, overlay, 0.00755F, side / 512.0D);
            }
        });
    }
    private static void emit(MonitorClientState.View view, double cx, double cz, MapFacing facing,
            double ox, double oz, double diameter, float partialTick, Sink sink) {
        double time = MonitorClientState.renderTime(partialTick);
        for (var target : view.targets()) {
            if (!target.hasPosition()) continue;
            float opacity = view.targetOpacity(target, time);
            if (opacity <= 0) continue;
            int rgb = MonitorTargetPalette.entityRgb(target);
            int fill = (Math.clamp(Math.round(64 * opacity), 0, 255) << 24) | rgb;
            int edge = (Math.clamp(Math.round(220 * opacity), 0, 255) << 24) | rgb;
            for (Silhouette.Quad quad : target.silhouette().fills()) {
                List<MonitorSectorProjection.MapPoint> points = new ArrayList<>(4);
                for (var p : List.of(quad.a(), quad.b(), quad.c(), quad.d())) points.add(project(p, cx, cz, facing, ox, oz, diameter));
                var clipped = MonitorSectorProjection.clipPolygon(points);
                for (int i = 1; i + 1 < clipped.size(); i++) sink.triangle(clipped.getFirst(), clipped.get(i), clipped.get(i + 1), fill);
            }
            for (var line : target.silhouette().outline()) {
                MonitorSectorProjection.clipSegment(project(line.start(), cx, cz, facing, ox, oz, diameter),
                        project(line.end(), cx, cz, facing, ox, oz, diameter)).ifPresent(segment -> sink.line(segment, edge));
            }
        }
    }
    private static MonitorSectorProjection.MapPoint project(RadarVisualGeometry.Point point,
            double cx, double cz, MapFacing facing, double ox, double oz, double diameter) {
        var right = facing.right();
        double x = point.x() - cx - ox, z = point.z() - cz - oz;
        double radius = Math.max(0.5D, diameter * 0.5D);
        return new MonitorSectorProjection.MapPoint((x * right.getStepX() + z * right.getStepZ()) / radius,
                (x * facing.getStepX() + z * facing.getStepZ()) / radius);
    }
}
