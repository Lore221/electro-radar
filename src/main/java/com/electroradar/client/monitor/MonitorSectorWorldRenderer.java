package com.electroradar.client.monitor;

import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Рисует ту же проецированную геометрию секторов на экранной плоскости монитора в мире. */
final class MonitorSectorWorldRenderer {
    private static final float FILL_FACE_OFFSET = 0.00635F;
    private static final float BASE_LINE_FACE_OFFSET = 0.00685F;
    private static final float ACTIVE_EDGE_FACE_OFFSET = 0.00735F;
    /** Полуширина выражена долей видимого квадрата, поэтому сохраняется на сборках 1×1–5×5. */
    private static final double BASE_LINE_HALF_WIDTH_FRACTION = 1.0D / 360.0D;
    private static final double ACTIVE_LINE_HALF_WIDTH_FRACTION = 1.0D / 260.0D;
    private static boolean styleLogged;

    private MonitorSectorWorldRenderer() {
    }

    /**
     * Наносит базовую область и активную часть до целей. Глубины остаются ближе к текстуре фона,
     * чем существующие метки, поэтому анимация не перекрывает их и не меняет выбор цели.
     */
    static void draw(RadarDisplayBlockEntity display, PoseStack poseStack, MultiBufferSource buffers,
                     Direction facing, BlockPos origin, MonitorClientState.View view,
                     MonitorSnapshotData snapshot, double worldCenterX, double worldCenterZ, double mapDiameter,
                     double centerU, double centerV, double contentLeft, double contentBottom,
                     double contentSide, double mapRadius, int packedOverlay) {
        if (snapshot.visualProfiles().isEmpty()) return;
        if (!styleLogged) {
            styleLogged = true;
            RadarDiagnostics.log("monitor_visual_world_style", null, null,
                    snapshot.sequence(), null,
                    "clock=client guiBaseAlpha=" + (MonitorSectorPalette.baseLine() >>> 24)
                            + " worldBaseAlpha=" + (MonitorSectorPalette.worldBaseLine() >>> 24)
                            + " worldActiveAlpha=" + (MonitorSectorPalette.worldActiveEdge() >>> 24)
                            + " baseHalfWidthFraction=" + BASE_LINE_HALF_WIDTH_FRACTION
                            + " activeHalfWidthFraction=" + ACTIVE_LINE_HALF_WIDTH_FRACTION);
        }
        Map<RadarVisualProfile.Key, MonitorSectorAnimationState.ProfileFrame> frameByProfile =
                MonitorSectorMesh.framesByProfile(view);

        VertexConsumer consumer = buffers.getBuffer(
                RenderType.entityTranslucentEmissive(MonitorBlipSprite.TEXTURE));
        double baseLineHalfWidth = contentSide * BASE_LINE_HALF_WIDTH_FRACTION;
        double activeLineHalfWidth = contentSide * ACTIVE_LINE_HALF_WIDTH_FRACTION;
        MonitorSectorMesh.Style colors = MonitorSectorPalette.worldStyle();
        MonitorSectorMesh.Sink sink = new MonitorSectorMesh.Sink() {
            @Override
            public void triangle(MonitorSectorProjection.MapPoint first, MonitorSectorProjection.MapPoint second,
                                 MonitorSectorProjection.MapPoint third, int argb) {
                drawTriangle(display, poseStack, consumer, facing, origin, first, second, third,
                        centerU, centerV, mapRadius, contentLeft, contentBottom, contentSide,
                        argb, packedOverlay, FILL_FACE_OFFSET);
            }

            @Override
            public void line(MonitorSectorProjection.ProjectedSegment line, int argb, MonitorSectorMesh.LineStyle style) {
                boolean active = style == MonitorSectorMesh.LineStyle.ACTIVE;
                drawLine(display, poseStack, consumer, facing, origin, line,
                        centerU, centerV, mapRadius, contentLeft, contentBottom, contentSide,
                        argb, packedOverlay, active ? ACTIVE_EDGE_FACE_OFFSET : BASE_LINE_FACE_OFFSET,
                        active ? activeLineHalfWidth : baseLineHalfWidth);
            }
        };
        for (RadarVisualProfile profile : snapshot.visualProfiles()) {
            MonitorSectorProjection.ProjectedCoverage coverage = MonitorSectorProjection.project(
                    profile, snapshot.dimension(), worldCenterX, worldCenterZ, facing,
                    0.0D, 0.0D, mapDiameter);
            MonitorSectorMesh.emit(coverage, profile.animation(), frameByProfile.get(profile.key()), colors, sink);
        }
    }

    /** Преобразует треугольник общего прохода в поверхность монитора. */
    private static void drawTriangle(RadarDisplayBlockEntity display, PoseStack poseStack,
                                   VertexConsumer consumer, Direction facing, BlockPos origin,
                                   MonitorSectorProjection.MapPoint first,
                                   MonitorSectorProjection.MapPoint second,
                                   MonitorSectorProjection.MapPoint third,
                                   double centerU, double centerV, double mapRadius,
                                   double contentLeft, double contentBottom, double contentSide,
                                   int argb, int packedOverlay, float faceOffset) {
        int alpha = argb >>> 24;
        int red = argb >>> 16 & 0xFF;
        int green = argb >>> 8 & 0xFF;
        int blue = argb & 0xFF;
        vertex(display, poseStack, consumer, facing, origin, first, centerU, centerV, mapRadius,
                contentLeft, contentBottom, contentSide, red, green, blue, alpha, packedOverlay, faceOffset);
        vertex(display, poseStack, consumer, facing, origin, second, centerU, centerV, mapRadius,
                contentLeft, contentBottom, contentSide, red, green, blue, alpha, packedOverlay, faceOffset);
        vertex(display, poseStack, consumer, facing, origin, third, centerU, centerV, mapRadius,
                contentLeft, contentBottom, contentSide, red, green, blue, alpha, packedOverlay, faceOffset);
        // RenderType использует QUADS: повтор вершины задаёт один невырожденный треугольник.
        vertex(display, poseStack, consumer, facing, origin, third, centerU, centerV, mapRadius,
                contentLeft, contentBottom, contentSide, red, green, blue, alpha, packedOverlay, faceOffset);
    }

    /** Представляет линию тонким quad, отсекая также его толщину по внутреннему квадрату карты. */
    private static void drawLine(RadarDisplayBlockEntity display, PoseStack poseStack,
                                 VertexConsumer consumer, Direction facing, BlockPos origin,
                                 MonitorSectorProjection.ProjectedSegment line,
                                 double centerU, double centerV, double mapRadius,
                                 double contentLeft, double contentBottom, double contentSide,
                                 int argb, int packedOverlay, float faceOffset, double halfWidth) {
        double x1 = centerU + line.start().x() * mapRadius;
        double y1 = centerV + line.start().y() * mapRadius;
        double x2 = centerU + line.end().x() * mapRadius;
        double y2 = centerV + line.end().y() * mapRadius;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-8D) return;
        double normalX = -dy / length * halfWidth;
        double normalY = dx / length * halfWidth;
        double minU = contentLeft;
        double maxU = contentLeft + contentSide;
        double minV = contentBottom;
        double maxV = contentBottom + contentSide;
        double[] u = {clamp(x1 + normalX, minU, maxU), clamp(x2 + normalX, minU, maxU),
                clamp(x2 - normalX, minU, maxU), clamp(x1 - normalX, minU, maxU)};
        double[] v = {clamp(y1 + normalY, minV, maxV), clamp(y2 + normalY, minV, maxV),
                clamp(y2 - normalY, minV, maxV), clamp(y1 - normalY, minV, maxV)};
        int alpha = argb >>> 24;
        int red = argb >>> 16 & 0xFF;
        int green = argb >>> 8 & 0xFF;
        int blue = argb & 0xFF;
        for (int i = 0; i < 4; i++) {
            RadarDisplayRenderer.surfaceVertex(consumer, poseStack, display, origin, facing,
                    u[i], v[i], whiteU(), whiteV(), red, green, blue, alpha, packedOverlay, faceOffset);
        }
    }

    private static void vertex(RadarDisplayBlockEntity display, PoseStack poseStack,
                               VertexConsumer consumer, Direction facing, BlockPos origin,
                               MonitorSectorProjection.MapPoint point,
                               double centerU, double centerV, double mapRadius,
                               double contentLeft, double contentBottom, double contentSide,
                               int red, int green, int blue, int alpha, int packedOverlay,
                               float faceOffset) {
        double u = clamp(centerU + point.x() * mapRadius, contentLeft, contentLeft + contentSide);
        double v = clamp(centerV + point.y() * mapRadius, contentBottom, contentBottom + contentSide);
        RadarDisplayRenderer.surfaceVertex(consumer, poseStack, display, origin, facing,
                u, v, whiteU(), whiteV(), red, green, blue, alpha, packedOverlay, faceOffset);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float whiteU() {
        return (MonitorBlipSprite.WHITE_PIXEL.x() + 0.5F) / MonitorBlipSprite.ATLAS_SIZE;
    }

    private static float whiteV() {
        return (MonitorBlipSprite.WHITE_PIXEL.y() + 0.5F) / MonitorBlipSprite.ATLAS_SIZE;
    }
}
