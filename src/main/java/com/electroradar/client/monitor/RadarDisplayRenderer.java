package com.electroradar.client.monitor;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.electroradar.ElectroRadar;
import com.electroradar.config.RadarClientConfig;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.RadarDisplayBlock;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.RadarDisplayProjector;
import com.electroradar.radar.monitor.RadarDisplayStructureResolver;

/**
 * Рисует одну общую карту от корневой панели. Рамки панелей и CT остаются моделям блоков;
 * здесь отрисовывается только квадратная поверхность карты и данные текущего снимка клиента.
 *
 * <p>BER вызывается на клиентском потоке. Он не ищет сущностей в мире и не хранит вторую копию
 * целей: позиции и затухание читаются только из {@link MonitorClientState}.</p>
 */
public final class RadarDisplayRenderer implements BlockEntityRenderer<RadarDisplayBlockEntity> {
    private static final ResourceLocation SCREEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ElectroRadar.MOD_ID, "textures/gui/radar_monitor/radar_screen_back.png");

    /** Временный тестовый радар показывает диаметр карты 200 блоков; CEE тут нет. */
    static final double MAP_DIAMETER_BLOCKS = 200.0D;
    /** Опорный диапазон для размера метки; совпадает с фиксированным диапазоном тестового экрана. */
    private static final double REFERENCE_MAP_DIAMETER_BLOCKS = 200.0D;
    /** Физический внешний отступ: два пикселя 16-пиксельной панели с каждой стороны. */
    private static final double PANEL_FRAME_INSET = 2.0D / 16.0D;
    /** Рамка внутри 128-пиксельной текстуры radar_screen_back. */
    private static final double TEXTURE_FRAME_INSET = 3.0D / 128.0D;
    /** Экранная плоскость старой модели находится в 3/16 блока от центра по нормали. */
    private static final double PANEL_FACE_FROM_CENTER = 3.0D / 16.0D;
    private static final int SCREEN_LIGHT = 0x00F000F0;

    /* Малые смещения от плоскости панели задают порядок полупрозрачных слоёв и убирают z-fighting. */
    private static final float BACKGROUND_FACE_OFFSET = 0.00600F;
    private static final float MARKER_FACE_OFFSET = 0.00915F;
    private static final float MARKER_FACE_OFFSET_STEP = 0.00010F;
    private static final int MARKER_DEPTH_LAYER_COUNT = 16;
    private static final float SELECTED_FRAME_FACE_OFFSET =
            MARKER_FACE_OFFSET + MARKER_DEPTH_LAYER_COUNT * MARKER_FACE_OFFSET_STEP;
    private static final float ALPHA_REINFORCEMENT_OFFSET = 0.00001F;
    private static final float VERTICAL_GRID_FACE_OFFSET = 0.01095F;
    private static final float HORIZONTAL_GRID_FACE_OFFSET = 0.01115F;
    private static final float MARKER_RENDER_SCALE = 0.85F;
    private static final float GRID_WHITE_PIXEL_U =
            (MonitorBlipSprite.WHITE_PIXEL.x() + 0.5F) / MonitorBlipSprite.ATLAS_SIZE;
    private static final float GRID_WHITE_PIXEL_V =
            (MonitorBlipSprite.WHITE_PIXEL.y() + 0.5F) / MonitorBlipSprite.ATLAS_SIZE;

    public RadarDisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * Отрисовывает экран только из текущего корня и активных габаритов сборки.
     * Square ограничивается короткой стороной, затем центрируется в геометрическом центре
     * прямоугольника — поэтому сборки 1×3 и 2×3 не растягивают изображение.
     */
    @Override
    public void render(RadarDisplayBlockEntity display, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!display.isRoot() || display.displayId() == null
                || display.rectangleWidth() < 1 || display.rectangleHeight() < 1) {
            return;
        }

        Direction facing = display.getBlockState().getValue(RadarDisplayBlock.FACING);
        int width = display.rectangleWidth();
        int height = display.rectangleHeight();
        BlockPos origin = display.rectangleOrigin();
        double screenSide = Math.min(width, height);
        double centerU = width * 0.5D;
        double centerV = height * 0.5D;
        double outerLeft = centerU - screenSide * 0.5D;
        double outerBottom = centerV - screenSide * 0.5D;

        // Сначала отступаем от физической рамки панели, затем от декоративной рамки текстуры.
        double backgroundSide = Math.max(0.02D, screenSide - PANEL_FRAME_INSET * 2.0D);
        double backgroundLeft = outerLeft + PANEL_FRAME_INSET;
        double backgroundBottom = outerBottom + PANEL_FRAME_INSET;
        double textureInset = backgroundSide * TEXTURE_FRAME_INSET;
        double contentLeft = backgroundLeft + textureInset;
        double contentBottom = backgroundBottom + textureInset;
        double contentSide = Math.max(0.01D, backgroundSide - textureInset * 2.0D);
        double mapRadius = contentSide * 0.5D;

        poseStack.pushPose();
        drawTexturedBackground(display, poseStack, buffers, facing, origin,
                backgroundLeft, backgroundBottom, backgroundLeft + backgroundSide,
                backgroundBottom + backgroundSide, packedOverlay);
        drawMapLayers(display, poseStack, buffers, facing, origin, width, height,
                centerU, centerV, contentLeft, contentBottom, contentSide, mapRadius,
                packedOverlay, partialTick);
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(RadarDisplayBlockEntity display) {
        return display.isRoot();
    }

    /** Расширяет culling box корневого BER до всего активного прямоугольника. */
    @Override
    public AABB getRenderBoundingBox(RadarDisplayBlockEntity display) {
        if (display.rectangleWidth() < 1 || display.rectangleHeight() < 1) {
            return new AABB(display.getBlockPos());
        }
        Direction facing = display.getBlockState().getValue(RadarDisplayBlock.FACING);
        Direction right = RadarDisplayStructureResolver.right(facing);
        BlockPos origin = display.rectangleOrigin();
        BlockPos opposite = origin.relative(right, display.rectangleWidth()).above(display.rectangleHeight());
        return new AABB(Math.min(origin.getX(), opposite.getX()), Math.min(origin.getY(), opposite.getY()),
                Math.min(origin.getZ(), opposite.getZ()), Math.max(origin.getX(), opposite.getX()) + 1.0D,
                Math.max(origin.getY(), opposite.getY()) + 1.0D,
                Math.max(origin.getZ(), opposite.getZ()) + 1.0D).inflate(0.25D);
    }

    /** Фон радарного экрана занимает только квадратную область за рамкой каждой панели. */
    private static void drawTexturedBackground(RadarDisplayBlockEntity display, PoseStack poseStack,
                                               MultiBufferSource buffers, Direction facing, BlockPos origin,
                                               double left, double bottom, double right, double top,
                                               int packedOverlay) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucentEmissive(SCREEN_TEXTURE));
        surfaceVertex(consumer, poseStack, display, origin, facing, left, bottom, 0.0F, 1.0F,
                255, 255, 255, 255, packedOverlay, BACKGROUND_FACE_OFFSET);
        surfaceVertex(consumer, poseStack, display, origin, facing, right, bottom, 1.0F, 1.0F,
                255, 255, 255, 255, packedOverlay, BACKGROUND_FACE_OFFSET);
        surfaceVertex(consumer, poseStack, display, origin, facing, right, top, 1.0F, 0.0F,
                255, 255, 255, 255, packedOverlay, BACKGROUND_FACE_OFFSET);
        surfaceVertex(consumer, poseStack, display, origin, facing, left, top, 0.0F, 0.0F,
                255, 255, 255, 255, packedOverlay, BACKGROUND_FACE_OFFSET);
    }

    /** Рисует область обзора под метками, затем сами метки/рамки и прежнюю мировую сетку. */
    private static void drawMapLayers(RadarDisplayBlockEntity display, PoseStack poseStack,
                                      MultiBufferSource buffers, Direction facing, BlockPos origin,
                                      int width, int height, double centerU, double centerV,
                                      double contentLeft, double contentBottom, double contentSide,
                                      double mapRadius, int packedOverlay, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        MonitorClientState.View view = null;
        if (minecraft.level != null) {
            MonitorDisplayKey key = new MonitorDisplayKey(minecraft.level.dimension().location(),
                    display.rootPosition(), display.displayId(), display.loadId());
            view = MonitorClientState.get(key, partialTick);
        }
        MonitorSnapshotData snapshot = view == null ? null : view.snapshot();
        double centerX = display.rectangleOrigin().getX() + 0.5D;
        double centerZ = display.rectangleOrigin().getZ() + 0.5D;
        double squareSide = Math.min(width, height);
        Direction right = RadarDisplayStructureResolver.right(facing);
        centerX += right.getStepX() * ((width - 1) * 0.5D);
        centerZ += right.getStepZ() * ((width - 1) * 0.5D);

        if (view != null && snapshot != null) {
            MonitorSectorWorldRenderer.draw(display, poseStack, buffers, facing, origin,
                    view, snapshot, centerX, centerZ, centerU, centerV,
                    contentLeft, contentBottom, contentSide, mapRadius, packedOverlay);
            drawTargetIcons(display, poseStack, buffers, facing, origin, view, snapshot,
                    centerX, centerZ, centerU, centerV, contentLeft, contentBottom, contentSide,
                    mapRadius, squareSide, packedOverlay, partialTick);
            drawSelectedFrames(display, poseStack, buffers, facing, origin, view, snapshot,
                    centerX, centerZ, centerU, centerV, contentLeft, contentBottom, contentSide,
                    mapRadius, squareSide, packedOverlay, partialTick);
        }

        // Линии не притворяются секторами или CEE: это только масштабная мировая координатная сетка.
        drawGrid(display, poseStack, buffers, facing, origin, centerX, centerZ,
                centerU, centerV, contentLeft, contentBottom, contentSide, mapRadius, packedOverlay);
    }

    /** Первый проход меток: атласный знак с размером ячейки, зависящим от масштаба карты. */
    private static void drawTargetIcons(RadarDisplayBlockEntity display, PoseStack poseStack,
                                        MultiBufferSource buffers, Direction facing, BlockPos origin,
                                        MonitorClientState.View view, MonitorSnapshotData snapshot,
                                        double centerX, double centerZ, double centerU, double centerV,
                                        double contentLeft, double contentBottom, double contentSide,
                                        double mapRadius, double squareSide, int packedOverlay,
                                        float partialTick) {
        int index = 0;
        double renderTime = MonitorClientState.renderTime(partialTick);
        double markerHalf = markerCellHalfSize(squareSide);
        for (MonitorClientState.DisplayTarget target : view.targets()) {
            if (!target.hasPosition()) continue;
            var point = RadarDisplayProjector.project(target.target().dimension().location(), snapshot.dimension(),
                    centerX, centerZ, facing, target.x(), target.z(), 0.0D, 0.0D, MAP_DIAMETER_BLOCKS);
            if (point == null || !intersectsMap(point.x(), point.y(), markerHalf, mapRadius)) continue;

            double x = centerU + point.x() * mapRadius;
            double y = centerV + point.y() * mapRadius;
            float opacity = view.targetOpacity(target, renderTime);
            if (opacity <= 0.0F) continue;
            int alpha = Math.clamp(Math.round(255.0F * opacity), 0, 255);
            int color = MonitorTargetPalette.entityRgb(target);
            float depth = MARKER_FACE_OFFSET + (index++ % MARKER_DEPTH_LAYER_COUNT) * MARKER_FACE_OFFSET_STEP;
            drawSprite(display, poseStack, buffers, facing, origin, MonitorBlipSprite.ENTITY,
                    contentLeft, contentBottom, contentSide, x, y, markerHalf,
                    color, alpha, packedOverlay, depth);
            // В atlas встречаются намеренно полупрозрачные пиксели. Второй близкий проход
            // сохраняет их читаемость, при этом обе копии получают тот же коэффициент затухания.
            drawSprite(display, poseStack, buffers, facing, origin, MonitorBlipSprite.ENTITY,
                    contentLeft, contentBottom, contentSide, x, y, markerHalf,
                    color, alpha, packedOverlay, depth + ALPHA_REINFORCEMENT_OFFSET);
        }
    }

    /** Второй проход рамок гарантирует, что выбранная сервером цель находится над соседними метками. */
    private static void drawSelectedFrames(RadarDisplayBlockEntity display, PoseStack poseStack,
                                           MultiBufferSource buffers, Direction facing, BlockPos origin,
                                           MonitorClientState.View view, MonitorSnapshotData snapshot,
                                           double centerX, double centerZ, double centerU, double centerV,
                                           double contentLeft, double contentBottom, double contentSide,
                                           double mapRadius, double squareSide, int packedOverlay,
                                           float partialTick) {
        if (snapshot.selectedTarget() == null) return;
        double gameTime = MonitorClientState.renderTime(partialTick);
        double frameHalf = markerCellHalfSize(squareSide);
        for (MonitorClientState.DisplayTarget target : view.targets()) {
            if (!target.hasPosition() || !snapshot.selectedTarget().equals(target.target())) continue;
            var point = RadarDisplayProjector.project(target.target().dimension().location(), snapshot.dimension(),
                    centerX, centerZ, facing, target.x(), target.z(), 0.0D, 0.0D, MAP_DIAMETER_BLOCKS);
            if (point == null || !intersectsMap(point.x(), point.y(), frameHalf, mapRadius)) continue;

            double x = centerU + point.x() * mapRadius;
            double y = centerV + point.y() * mapRadius;
            int alpha = Math.clamp(Math.round(255.0F * target.rememberedOpacity(gameTime)), 0, 255);
            drawSprite(display, poseStack, buffers, facing, origin, MonitorBlipSprite.SELECTED_FRAME,
                    contentLeft, contentBottom, contentSide, x, y, frameHalf,
                    RadarClientConfig.palette().selectedFrame() & 0x00FFFFFF,
                    alpha, packedOverlay, SELECTED_FRAME_FACE_OFFSET);
            drawSprite(display, poseStack, buffers, facing, origin, MonitorBlipSprite.SELECTED_FRAME,
                    contentLeft, contentBottom, contentSide, x, y, frameHalf,
                    RadarClientConfig.palette().selectedFrame() & 0x00FFFFFF, alpha, packedOverlay,
                    SELECTED_FRAME_FACE_OFFSET + ALPHA_REINFORCEMENT_OFFSET);
            return;
        }
    }

    /** Вычисляет полуширину ячейки 8×8 от стороны дисплея, как в прежнем рендерере. */
    private static double markerCellHalfSize(double screenSide) {
        return screenSide * (MonitorBlipSprite.CELL_SIZE / 128.0D) * MARKER_RENDER_SCALE
                * (REFERENCE_MAP_DIAMETER_BLOCKS / MAP_DIAMETER_BLOCKS) * 0.5D;
    }

    /** Оставляет иконку в проходе рендера, если её quad хотя бы частично пересекает карту. */
    private static boolean intersectsMap(double x, double y, double halfSize, double radius) {
        return Math.abs(x) * radius - halfSize < radius
                && Math.abs(y) * radius - halfSize < radius;
    }

    /** Сетка строится по глобальным кратным X/Z и преобразуется в оси квадрата сборки. */
    private static void drawGrid(RadarDisplayBlockEntity display, PoseStack poseStack,
                                 MultiBufferSource buffers, Direction facing, BlockPos origin,
                                 double worldCenterX, double worldCenterZ, double centerU, double centerV,
                                 double contentLeft, double contentBottom, double contentSide,
                                 double mapRadius, int packedOverlay) {
        double lineHalfWidth = Math.max(0.0008D, contentSide / 1024.0D);
        int gridColor = RadarClientConfig.palette().worldGrid();
        float red = (gridColor >>> 16) & 0xFF;
        float green = (gridColor >>> 8) & 0xFF;
        float blue = gridColor & 0xFF;
        int alpha = (gridColor >>> 24) & 0xFF;
        List<MonitorGridGeometry.GridLine> lines = MonitorGridGeometry.project(
                worldCenterX, worldCenterZ, 0.0D, 0.0D, facing, MAP_DIAMETER_BLOCKS);
        for (MonitorGridGeometry.GridLine line : lines) {
            double offset = line.normalizedCoordinate() * mapRadius;
            if (line.vertical()) {
                double x = centerU + offset;
                double clippedLeft = Math.max(contentLeft, x - lineHalfWidth);
                double clippedRight = Math.min(contentLeft + contentSide, x + lineHalfWidth);
                if (clippedRight <= clippedLeft) continue;
                drawSolidAtlasRect(display, poseStack, buffers, facing, origin,
                        clippedLeft, contentBottom, clippedRight, contentBottom + contentSide,
                        red, green, blue, alpha,
                        packedOverlay, VERTICAL_GRID_FACE_OFFSET);
            } else {
                double y = centerV - offset;
                double clippedBottom = Math.max(contentBottom, y - lineHalfWidth);
                double clippedTop = Math.min(contentBottom + contentSide, y + lineHalfWidth);
                if (clippedTop <= clippedBottom) continue;
                drawSolidAtlasRect(display, poseStack, buffers, facing, origin,
                        contentLeft, clippedBottom, contentLeft + contentSide, clippedTop,
                        red, green, blue, alpha,
                        packedOverlay, HORIZONTAL_GRID_FACE_OFFSET);
            }
        }
    }

    /** Рисует атласную прозрачную метку; UV берутся из atlas, а не из отдельной текстуры. */
    private static void drawSprite(RadarDisplayBlockEntity display, PoseStack poseStack,
                                   MultiBufferSource buffers, Direction facing, BlockPos origin,
                                   MonitorBlipSprite.Region sprite,
                                   double contentLeft, double contentBottom, double contentSide,
                                   double centerU, double centerV, double halfSize,
                                   int rgb, int alpha, int packedOverlay, float faceOffset) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucentEmissive(MonitorBlipSprite.TEXTURE));
        double left = centerU - halfSize;
        double right = centerU + halfSize;
        double bottom = centerV - halfSize;
        double top = centerV + halfSize;
        double clippedLeft = Math.max(contentLeft, left);
        double clippedRight = Math.min(contentLeft + contentSide, right);
        double clippedBottom = Math.max(contentBottom, bottom);
        double clippedTop = Math.min(contentBottom + contentSide, top);
        if (clippedRight <= clippedLeft || clippedTop <= clippedBottom) return;

        /*
         * Обрезка делается в координатах карты, а не простым прижатием центра: видимая часть
         * сохраняет исходную пропорцию atlas. U возрастает слева направо; для экранной V
         * нижняя вершина использует maxV, поэтому её UV интерполируется от maxV к minV.
         */
        double width = right - left;
        double height = top - bottom;
        float minU = interpolate(sprite.minU(), sprite.maxU(), (clippedLeft - left) / width);
        float maxU = interpolate(sprite.minU(), sprite.maxU(), (clippedRight - left) / width);
        float bottomV = interpolate(sprite.maxV(), sprite.minV(), (clippedBottom - bottom) / height);
        float topV = interpolate(sprite.maxV(), sprite.minV(), (clippedTop - bottom) / height);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        surfaceVertex(consumer, poseStack, display, origin, facing, clippedLeft, clippedBottom,
                minU, bottomV, red, green, blue, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, clippedRight, clippedBottom,
                maxU, bottomV, red, green, blue, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, clippedRight, clippedTop,
                maxU, topV, red, green, blue, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, clippedLeft, clippedTop,
                minU, topV, red, green, blue, alpha, packedOverlay, faceOffset);
    }

    /** Линейная интерполяция границ atlas при отсечении quad по рабочему квадрату карты. */
    private static float interpolate(float start, float end, double fraction) {
        return (float) (start + (end - start) * fraction);
    }

    /** Белый пиксель atlas даёт ровный цвет сетки без новых текстур и без debug-render type. */
    private static void drawSolidAtlasRect(RadarDisplayBlockEntity display, PoseStack poseStack,
                                          MultiBufferSource buffers, Direction facing, BlockPos origin,
                                          double left, double bottom, double right, double top,
                                          float red, float green, float blue, int alpha,
                                          int packedOverlay, float faceOffset) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucentEmissive(MonitorBlipSprite.TEXTURE));
        int r = Math.round(red);
        int g = Math.round(green);
        int b = Math.round(blue);
        surfaceVertex(consumer, poseStack, display, origin, facing, left, bottom,
                GRID_WHITE_PIXEL_U, GRID_WHITE_PIXEL_V, r, g, b, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, right, bottom,
                GRID_WHITE_PIXEL_U, GRID_WHITE_PIXEL_V, r, g, b, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, right, top,
                GRID_WHITE_PIXEL_U, GRID_WHITE_PIXEL_V, r, g, b, alpha, packedOverlay, faceOffset);
        surfaceVertex(consumer, poseStack, display, origin, facing, left, top,
                GRID_WHITE_PIXEL_U, GRID_WHITE_PIXEL_V, r, g, b, alpha, packedOverlay, faceOffset);
    }

    /**
     * Создаёт мировую вершину в плоскости панели. Положительный faceOffset всегда направлен
     * к игроку для N/E/S/W; полный lightmap исключает зависимость свечения экрана от освещения.
     */
    static void surfaceVertex(VertexConsumer consumer, PoseStack poseStack,
                              RadarDisplayBlockEntity display, BlockPos origin, Direction facing,
                              double u, double v, float textureU, float textureV,
                              int red, int green, int blue, int alpha,
                              int packedOverlay, float faceOffset) {
        Direction right = RadarDisplayStructureResolver.right(facing);
        Vec3 position = new Vec3(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D)
                .add(right.getStepX() * (u - 0.5D), v - 0.5D, right.getStepZ() * (u - 0.5D))
                .add(facing.getStepX() * (PANEL_FACE_FROM_CENTER + faceOffset), 0,
                        facing.getStepZ() * (PANEL_FACE_FROM_CENTER + faceOffset))
                .subtract(display.getBlockPos().getX(), display.getBlockPos().getY(),
                        display.getBlockPos().getZ());
        consumer.addVertex(poseStack.last().pose(), (float) position.x, (float) position.y, (float) position.z)
                .setColor(red, green, blue, alpha)
                .setUv(textureU, textureV)
                .setOverlay(packedOverlay)
                .setLight(SCREEN_LIGHT)
                .setNormal(facing.getStepX(), facing.getStepY(), facing.getStepZ());
    }
}
