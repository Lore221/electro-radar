package com.electroradar.client.monitor;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.ElectroRadar;
import com.electroradar.config.RadarClientConfig;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.RadarDisplayBlock;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.RadarDisplayProjector;
import com.electroradar.radar.monitor.RadarDisplayStructureResolver;
import com.electroradar.radar.monitor.network.MonitorSelectPayload;

/**
 * Квадратный GUI одного физического дисплея.
 *
 * <p>Снимок и состояние затухания принадлежат {@link MonitorClientState}; в этом классе живут
 * только временный масштаб, сдвиг и состояние мыши. Выбор всегда отправляется серверу, а
 * перетаскивание меняет исключительно локальный центр проекции.</p>
 */
public final class RadarMonitorScreen extends Screen {
    private static final ResourceLocation GUI_BACKGROUND = texture("monitor_gui_background");
    private static final ResourceLocation SCREEN_BACK = texture("radar_screen_back");
    private static final ResourceLocation RADAR_ICONS = ResourceLocation.fromNamespaceAndPath(
            ElectroRadar.MOD_ID, "textures/gui/radar_ui/icons.png");

    private static final int BACKGROUND_TEXTURE_SIZE = 32;
    private static final int ICON_ATLAS_SIZE = 256;
    private static final int SCREEN_TEXTURE_SIZE = 128;
    private static final int GUI_HEIGHT_PERCENT = 90;
    private static final int GUI_BACKGROUND_INSET_PIXELS = 2;
    private static final int SCREEN_BACKGROUND_INSET_PIXELS = 3;
    private static final int OUTER_MARGIN = 8;
    private static final int MIN_MAP_DIAMETER_BLOCKS = 100;
    private static final int MAX_MAP_DIAMETER_BLOCKS = 10_000;
    private static final int MAP_ZOOM_STEP_BLOCKS = 100;
    private static final int REFERENCE_MAP_DIAMETER_BLOCKS = 200;
    private static final int TARGET_ICON_SCALE = 85;

    /* Старый atlas содержит три отметки шага: 100, 500 и 1000 блоков. */
    private static final int GRID_SCALE_SOURCE_X = 234;
    private static final int GRID_SCALE_SOURCE_WIDTH = 22;
    private static final int GRID_SCALE_SOURCE_HEIGHT = 8;
    private static final int GRID_SCALE_DESTINATION_X = 221;
    private static final int GRID_SCALE_DESTINATION_Y = 235;
    private static final int GRID_SCALE_100_Y = 214;
    private static final int GRID_SCALE_500_Y = 223;
    private static final int GRID_SCALE_1000_Y = 232;

    private final MonitorDisplayKey key;

    /** Координаты внешнего квадратного корпуса на экране GUI. */
    private int guiX;
    private int guiY;
    private int guiSize;
    /** Квадрат текстуры radar_screen_back внутри корпуса. */
    private int mapX;
    private int mapY;
    private int mapSize;
    /** Внутренняя область карты после рамки 3/128, используемая проекцией и отсечением. */
    private int contentX;
    private int contentY;
    private int contentSize;

    private double mapDiameterBlocks = MonitorMapScale.FALLBACK_DIAMETER_BLOCKS;
    private double maxMapDiameterBlocks = MAX_MAP_DIAMETER_BLOCKS;
    /** После первого зума или перетаскивания новые снимки не меняют пользовательский вид. */
    private boolean manualViewport;
    private double centerOffsetX;
    private double centerOffsetZ;
    private boolean leftMouseDown;
    private boolean dragging;
    private double pressStartX;
    private double pressStartY;
    private double lastDragX;
    private double lastDragY;

    public RadarMonitorScreen(MonitorDisplayKey key) {
        super(Component.translatable("screen.electroradar.monitor.title"));
        this.key = key;
    }

    /** Позволяет клиентскому кэшу закрыть GUI при выгрузке или замене его корневой панели. */
    public boolean isFor(MonitorDisplayKey other) {
        return key.equals(other);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Рисует внешний фон только внутри центрального квадратного корпуса. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (guiSize > 0) {
            graphics.blit(GUI_BACKGROUND, guiX, guiY, guiSize, guiSize,
                    0, 0, BACKGROUND_TEXTURE_SIZE, BACKGROUND_TEXTURE_SIZE,
                    BACKGROUND_TEXTURE_SIZE, BACKGROUND_TEXTURE_SIZE);
        }
    }

    /** Сначала стандартная отрисовка Screen вызывает фон, затем поверх него рисуются слои карты. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        super.render(graphics, mouseX, mouseY, partialTick);
        drawMap(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!insideMap(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (scrollY != 0.0D) {
            manualViewport = true;
            int direction = scrollY > 0.0D ? -1 : 1;
            int steps = Math.max(1, (int) Math.round(Math.abs(scrollY)));
            mapDiameterBlocks = Math.max(MIN_MAP_DIAMETER_BLOCKS,
                    Math.min(maxMapDiameterBlocks,
                            mapDiameterBlocks + direction * (double) MAP_ZOOM_STEP_BLOCKS * steps));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && insideMap(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new MonitorSelectPayload(key.rootPosition(), key.displayId(), null));
            return true;
        }
        if (button == 0 && insideMap(mouseX, mouseY)) {
            // Выбор откладывается до отпускания кнопки: если игрок начал тащить карту
            // прямо с метки, промежуточное движение не должно послать случайный выбор.
            leftMouseDown = true;
            dragging = false;
            pressStartX = mouseX;
            pressStartY = mouseY;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (!leftMouseDown || button != 0) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        double fromPressX = mouseX - pressStartX;
        double fromPressY = mouseY - pressStartY;
        if (!dragging && fromPressX * fromPressX + fromPressY * fromPressY >= 4.0D) {
            dragging = true;
            lastDragX = pressStartX;
            lastDragY = pressStartY;
        }
        if (!dragging) return true;
        panMapTo(mouseX, mouseY);
        return true;
    }

    /** Перемещает только локальный центр карты по дельте мыши после преодоления drag-порога. */
    private void panMapTo(double mouseX, double mouseY) {
        manualViewport = true;
        double worldUnitsPerPixel = mapDiameterBlocks / (double) Math.max(1, contentSize);
        double deltaX = mouseX - lastDragX;
        double deltaY = mouseY - lastDragY;
        Direction facing = facing();
        Direction right = RadarDisplayStructureResolver.right(facing);
        // При движении мыши метки и сетка должны следовать за курсором, поэтому центр карты
        // сдвигается в противоположную горизонтальную и соответствующую вертикальную сторону.
        centerOffsetX += -deltaX * worldUnitsPerPixel * right.getStepX()
                + deltaY * worldUnitsPerPixel * facing.getStepX();
        centerOffsetZ += -deltaX * worldUnitsPerPixel * right.getStepZ()
                + deltaY * worldUnitsPerPixel * facing.getStepZ();
        lastDragX = mouseX;
        lastDragY = mouseY;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && leftMouseDown) {
            double fromPressX = mouseX - pressStartX;
            double fromPressY = mouseY - pressStartY;
            if (!dragging && fromPressX * fromPressX + fromPressY * fromPressY >= 4.0D) {
                dragging = true;
                lastDragX = pressStartX;
                lastDragY = pressStartY;
            }
            if (dragging) panMapTo(mouseX, mouseY);
            if (!dragging && insideMap(mouseX, mouseY)) {
                MonitorClientState.View view = MonitorClientState.get(key);
                if (view != null) {
                    findTargetAt(view, mouseX, mouseY).ifPresent(target -> {
                        if (target.detectedThisWindow() && target.hasPosition()) {
                            PacketDistributor.sendToServer(new MonitorSelectPayload(
                                    key.rootPosition(), key.displayId(), target.target()));
                        }
                    });
                }
            }
            leftMouseDown = false;
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Собирает слои в порядке старого экрана: фон, служебная информация, метки, рамки,
     * мировая сетка и значок её шага. Внутренняя рамка обрезает и метки, и линии.
     */
    private void drawMap(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(SCREEN_BACK, mapX, mapY, mapSize, mapSize,
                0, 0, SCREEN_TEXTURE_SIZE, SCREEN_TEXTURE_SIZE,
                SCREEN_TEXTURE_SIZE, SCREEN_TEXTURE_SIZE);

        MonitorClientState.View view = MonitorClientState.get(key, partialTick);
        MonitorSnapshotData snapshot = view == null ? null : view.snapshot();
        if (!manualViewport) {
            MapCenter center = mapCenter(facing());
            mapDiameterBlocks = MonitorMapScale.diameter(snapshot, center.x(), center.z());
            maxMapDiameterBlocks = Math.max(MAX_MAP_DIAMETER_BLOCKS, mapDiameterBlocks);
        }
        Optional<MonitorClientState.DisplayTarget> hovered = view == null
                ? Optional.empty() : findTargetAt(view, mouseX, mouseY);
        graphics.enableScissor(contentX, contentY, contentX + contentSize, contentY + contentSize);
        if (view != null && snapshot != null) {
            Direction facing = facing();
            MapCenter center = mapCenter(facing);
            MonitorSectorGuiRenderer.draw(graphics, view, snapshot.dimension(),
                    center.x(), center.z(), facing, centerOffsetX, centerOffsetZ,
                    mapDiameterBlocks, contentX, contentY, contentSize);
            drawTargets(graphics, view, snapshot, partialTick);
            drawTargetFrames(graphics, view, snapshot, hovered, partialTick);
        }
        drawGrid(graphics);
        graphics.disableScissor();
        drawGridScaleOverlay(graphics);
        drawStatus(graphics);
    }

    /** Выводит только те цели, для которых клиентский снимок содержит безопасную позицию. */
    private void drawTargets(GuiGraphics graphics, MonitorClientState.View view,
                             MonitorSnapshotData snapshot, float partialTick) {
        Direction facing = facing();
        int centerX = contentX + contentSize / 2;
        int centerY = contentY + contentSize / 2;
        double radius = contentSize / 2.0D;
        double renderTime = MonitorClientState.renderTime(partialTick);
        int cellPixels = markerCellPixels();

        for (MonitorClientState.DisplayTarget target : view.targets()) {
            if (!target.hasPosition()) continue;
            RadarDisplayProjector.MapPoint point = project(snapshot, target, facing);
            if (point == null || !point.insideSquare()) continue;
            int x = (int) Math.round(centerX + point.x() * radius);
            int y = (int) Math.round(centerY - point.y() * radius);
            float opacity = view.targetOpacity(target, renderTime);
            if (opacity <= 0.0F) continue;
            int alpha = Math.max(0, Math.min(255, Math.round(255.0F * opacity)));
            MonitorBlipSprite.drawGui(graphics, MonitorBlipSprite.ENTITY,
                    x, y, cellPixels, (alpha << 24) | MonitorTargetPalette.entityRgb(target));
        }
    }

    /** Рисует наведение и серверный выбор отдельными рамками поверх всех значков. */
    private void drawTargetFrames(GuiGraphics graphics, MonitorClientState.View view,
                                  MonitorSnapshotData snapshot,
                                  Optional<MonitorClientState.DisplayTarget> hovered,
                                  float partialTick) {
        Direction facing = facing();
        int centerX = contentX + contentSize / 2;
        int centerY = contentY + contentSize / 2;
        double radius = contentSize / 2.0D;
        double gameTime = MonitorClientState.renderTime(partialTick);
        int cellPixels = markerCellPixels();

        for (MonitorClientState.DisplayTarget target : view.targets()) {
            if (!target.hasPosition()) continue;
            RadarDisplayProjector.MapPoint point = project(snapshot, target, facing);
            if (point == null || !point.insideSquare()) continue;
            int x = (int) Math.round(centerX + point.x() * radius);
            int y = (int) Math.round(centerY - point.y() * radius);
            float opacity = target.rememberedOpacity(gameTime);
            int alpha = Math.max(0, Math.min(255, Math.round(255.0F * opacity)));
            boolean selected = snapshot.selectedTarget() != null
                    && snapshot.selectedTarget().equals(target.target());
            boolean isHovered = hovered.isPresent() && hovered.get().target().equals(target.target());
            if (selected) {
                MonitorBlipSprite.drawGui(graphics, MonitorBlipSprite.SELECTED_FRAME,
                        x, y, cellPixels, (alpha << 24)
                                | (RadarClientConfig.palette().screen().selectedFrame() & 0x00FFFFFF));
            } else if (isHovered) {
                MonitorBlipSprite.drawGui(graphics, MonitorBlipSprite.HOVERED_FRAME,
                        x, y, cellPixels, (alpha << 24)
                                | (RadarClientConfig.palette().screen().hoveredFrame() & 0x00FFFFFF));
            }
        }
    }

    /** Рисует мировые линии на масштабе карты и дополнительно страхует их scissor-отсечением. */
    private void drawGrid(GuiGraphics graphics) {
        MapCenter center = mapCenter(facing());
        for (MonitorGridGeometry.GridLine line : MonitorGridGeometry.project(
                center.x(), center.z(), centerOffsetX, centerOffsetZ, facing(), mapDiameterBlocks)) {
            double pixelOffset = line.normalizedCoordinate() * contentSize / 2.0D;
            if (line.vertical()) {
                int x = (int) Math.round(contentX + contentSize / 2.0D + pixelOffset);
                graphics.fill(x, contentY, x + 1, contentY + contentSize,
                        RadarClientConfig.palette().screen().guiGrid());
            } else {
                int y = (int) Math.round(contentY + contentSize / 2.0D + pixelOffset);
                graphics.fill(contentX, y, contentX + contentSize, y + 1,
                        RadarClientConfig.palette().screen().guiGrid());
            }
        }
    }

    /** Обычные шаги берутся из атласа, более крупный шаг удалённой сети выводится числом. */
    private void drawGridScaleOverlay(GuiGraphics graphics) {
        double gridStep = MonitorGridGeometry.stepBlocks(mapDiameterBlocks);
        int x = mapX + Math.round(mapSize * GRID_SCALE_DESTINATION_X / (float) ICON_ATLAS_SIZE);
        int y = mapY + Math.round(mapSize * GRID_SCALE_DESTINATION_Y / (float) ICON_ATLAS_SIZE);
        int iconWidth = Math.max(1, Math.round(mapSize * GRID_SCALE_SOURCE_WIDTH / (float) ICON_ATLAS_SIZE));
        int iconHeight = Math.max(1, Math.round(mapSize * GRID_SCALE_SOURCE_HEIGHT / (float) ICON_ATLAS_SIZE));
        if (gridStep > 1_000.0D) {
            graphics.drawString(font, Long.toString(Math.round(gridStep)), x, y,
                    RadarClientConfig.palette().screen().guiGrid() | 0xFF000000, false);
            return;
        }
        int sourceY = gridStep == 100.0D ? GRID_SCALE_100_Y
                : gridStep == 500.0D ? GRID_SCALE_500_Y : GRID_SCALE_1000_Y;
        graphics.blit(RADAR_ICONS, x, y, iconWidth, iconHeight,
                GRID_SCALE_SOURCE_X, sourceY, GRID_SCALE_SOURCE_WIDTH, GRID_SCALE_SOURCE_HEIGHT,
                ICON_ATLAS_SIZE, ICON_ATLAS_SIZE);
    }

    /**
     * Показывает короткое сообщение только тогда, когда состояние требует внимания.
     * В обычном рабочем режиме карта остаётся без постоянных заголовков, счётчиков и плашек.
     */
    private void drawStatus(GuiGraphics graphics) {
        MonitorClientState.View view = MonitorClientState.get(key);
        MonitorSnapshotData snapshot = view == null ? null : view.snapshot();
        Component status;
        int statusColor;
        if (snapshot == null) {
            status = Component.translatable("message.electroradar.monitor.waiting");
            statusColor = RadarClientConfig.palette().screen().statusWarning();
        } else if (!snapshot.linked()) {
            status = Component.translatable("message.electroradar.monitor.no_binding");
            statusColor = RadarClientConfig.palette().screen().statusWarning();
        } else if (snapshot.selectedTargetUnavailable()) {
            status = Component.translatable("message.electroradar.monitor.selection_unavailable");
            statusColor = RadarClientConfig.palette().screen().statusWarning();
        } else if (snapshot.targets().isEmpty()) {
            status = Component.translatable("message.electroradar.monitor.no_targets");
            statusColor = RadarClientConfig.palette().screen().statusNeutral();
        } else if (snapshot.targets().stream().anyMatch(target -> !target.detectedThisWindow())) {
            status = Component.translatable("message.electroradar.monitor.remembering");
            statusColor = RadarClientConfig.palette().screen().statusWarning();
        } else {
            return;
        }

        // У сообщения нет подложки: короткий текст рисуется внутри карты и обрезается её рамкой.
        graphics.enableScissor(contentX, contentY, contentX + contentSize, contentY + contentSize);
        graphics.drawCenteredString(font, status, contentX + contentSize / 2,
                contentY + 5, statusColor);
        graphics.disableScissor();
    }

    /** Находит ближайшую точку под курсором с радиусом, согласованным с масштабом её ячейки. */
    private Optional<MonitorClientState.DisplayTarget> findTargetAt(MonitorClientState.View view,
                                                                    double mouseX, double mouseY) {
        MonitorSnapshotData snapshot = view.snapshot();
        if (snapshot == null) return Optional.empty();
        Direction facing = facing();
        double centerX = contentX + contentSize / 2.0D;
        double centerY = contentY + contentSize / 2.0D;
        double radius = contentSize / 2.0D;
        double hitRadius = Math.max(5.0D, markerCellPixels() * 0.5D);
        return view.targets().stream()
                .filter(MonitorClientState.DisplayTarget::hasPosition)
                .map(target -> new Hit(target, project(snapshot, target, facing), centerX, centerY, radius))
                .filter(hit -> hit.point() != null && hit.point().insideSquare()
                        && hit.distance(mouseX, mouseY) <= hitRadius)
                .min((first, second) -> Double.compare(
                        first.distance(mouseX, mouseY), second.distance(mouseX, mouseY)))
                .map(Hit::target);
    }

    /** Проецирует цель от центра прямоугольной сборки; перетаскивание остаётся локальным GUI-сдвигом. */
    private RadarDisplayProjector.MapPoint project(MonitorSnapshotData snapshot,
                                                    MonitorClientState.DisplayTarget target,
                                                    Direction facing) {
        MapCenter center = mapCenter(facing);
        return RadarDisplayProjector.project(target.target().dimension().location(), snapshot.dimension(),
                center.x(), center.z(), facing, target.x(), target.z(), centerOffsetX, centerOffsetZ,
                mapDiameterBlocks);
    }

    /**
     * Возвращает центр всей загруженной сборки в мировых X/Z; при отсутствии геометрии
     * использует позицию корня. Чётная ширина даёт корректный центр между блоками.
     */
    private MapCenter mapCenter(Direction facing) {
        double centerX = key.rootPosition().getX() + 0.5D;
        double centerZ = key.rootPosition().getZ() + 0.5D;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.hasChunkAt(key.rootPosition())
                && minecraft.level.getBlockEntity(key.rootPosition()) instanceof RadarDisplayBlockEntity root
                && root.isRoot() && key.displayId().equals(root.displayId())
                && key.loadId().equals(root.loadId())
                && root.rectangleWidth() > 0 && root.rectangleHeight() > 0) {
            BlockPos origin = root.rectangleOrigin();
            Direction right = RadarDisplayStructureResolver.right(facing);
            double halfPanelOffset = (root.rectangleWidth() - 1) * 0.5D;
            centerX = origin.getX() + 0.5D + right.getStepX() * halfPanelOffset;
            centerZ = origin.getZ() + 0.5D + right.getStepZ() * halfPanelOffset;
        }
        return new MapCenter(centerX, centerZ);
    }

    /** Сторона 8×8 старой ячейки меняется обратно пропорционально видимому диапазону. */
    private int markerCellPixels() {
        // Эталон использовал сторону radar_screen_back, а не отступившую внутрь рабочую область.
        double pixelsAtReferenceScale = mapSize * (MonitorBlipSprite.CELL_SIZE / 128.0D)
                * (TARGET_ICON_SCALE / 100.0D);
        double scaledPixels = pixelsAtReferenceScale * REFERENCE_MAP_DIAMETER_BLOCKS
                / Math.max(MIN_MAP_DIAMETER_BLOCKS, mapDiameterBlocks);
        return Math.max(1, (int) Math.round(scaledPixels));
    }

    private Direction facing() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.hasChunkAt(key.rootPosition())) {
            BlockState state = minecraft.level.getBlockState(key.rootPosition());
            if (state.hasProperty(RadarDisplayBlock.FACING)) return state.getValue(RadarDisplayBlock.FACING);
        }
        return Direction.NORTH;
    }

    /** Рассчитывает квадрат корпуса, внутренний квадрат экранной текстуры и рабочее окно 3/128. */
    private void layout() {
        int desiredSize = Math.min(height * GUI_HEIGHT_PERCENT / 100, width - OUTER_MARGIN * 2);
        guiSize = Math.max(32, desiredSize);
        guiSize -= guiSize & 1;
        guiX = (width - guiSize) / 2;
        guiY = (height - guiSize) / 2;

        int housingInset = Math.max(1, (int) Math.ceil(
                guiSize * GUI_BACKGROUND_INSET_PIXELS / (double) BACKGROUND_TEXTURE_SIZE));
        mapSize = Math.max(1, guiSize - housingInset * 2);
        mapX = guiX + (guiSize - mapSize) / 2;
        mapY = guiY + (guiSize - mapSize) / 2;
        int textureInset = Math.max(1, (int) Math.ceil(
                mapSize * SCREEN_BACKGROUND_INSET_PIXELS / (double) SCREEN_TEXTURE_SIZE));
        contentSize = Math.max(1, mapSize - textureInset * 2);
        contentX = mapX + (mapSize - contentSize) / 2;
        contentY = mapY + (mapSize - contentSize) / 2;
    }

    private boolean insideMap(double x, double y) {
        return x >= contentX && x < contentX + contentSize
                && y >= contentY && y < contentY + contentSize;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID,
                "textures/gui/radar_monitor/" + name + ".png");
    }

    private record MapCenter(double x, double z) {
    }

    private record Hit(MonitorClientState.DisplayTarget target,
                       RadarDisplayProjector.MapPoint point,
                       double centerX, double centerY, double radius) {
        private double distance(double mouseX, double mouseY) {
            return Math.hypot(centerX + point.x() * radius - mouseX,
                    centerY - point.y() * radius - mouseY);
        }
    }
}
