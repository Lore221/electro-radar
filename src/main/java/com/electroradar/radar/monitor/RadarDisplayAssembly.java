package com.electroradar.radar.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

import com.electroradar.radar.network.NetworkId;

/**
 * Серверный резолвер физической сборки дисплея.
 *
 * <p>Класс вызывается только серверным тикером корня или после изменения панелей. Область обхода
 * ограничена девятью соседними координатами по каждой оси плоскости; неизвестные чанки не
 * подгружаются и не считаются отсутствующими блоками. Текстуры Create и сетевое соединение здесь
 * не управляются: результатом являются идентичность, прямоугольник и состояния блоков.</p>
 */
final class RadarDisplayAssembly {
    private RadarDisplayAssembly() {
    }

    /** Пересчитывает только полный прямоугольник до 5×5, содержащий текущий корень. */
    static void resolve(RadarDisplayBlockEntity root) {
        root.beginAssemblyResolution();
        if (!(root.getLevel() instanceof ServerLevel level)) return;

        BlockPos oldOrigin = root.rectangleOrigin();
        int oldWidth = root.rectangleWidth();
        int oldHeight = root.rectangleHeight();
        Direction facing = root.getBlockState().getValue(RadarDisplayBlock.FACING);
        UUID displayId = root.displayId();
        List<RadarDisplayBlockEntity> members = collectMembers(
                level, displayId, root.getBlockPos(), facing);
        if (members.isEmpty()) members = List.of(root);
        // Кроме панелей текущего направления, найдём бывших участников, которые Create мог
        // повернуть. Они должны потерять ACTIVE, иначе на старой плоскости останется второй экран.
        List<RadarDisplayBlockEntity> relatedPanels = collectNearbyPanels(
                level, displayId, root.getBlockPos());
        if (relatedPanels.isEmpty()) relatedPanels = members;

        NetworkId networkId = root.networkId();
        if (networkId == null) {
            // Копия NetworkId на панелях нужна только для восстановления после смены корня.
            networkId = members.stream().map(RadarDisplayBlockEntity::networkId)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (networkId != null) root.setNetworkIdFromAssembly(networkId);
        }

        Candidate previous = preserveCurrentRectangle(level, facing, displayId,
                root.rectangleOrigin(), root.rectangleWidth(), root.rectangleHeight());
        Candidate largest = chooseLargestRectangle(level, facing, root.getBlockPos(), members);
        // Сохраняем прежнюю геометрию при равном размере; завершённое расширение всегда выигрывает.
        Candidate chosen = previous != null && (largest == null || previous.area() >= largest.area())
                ? previous : largest;
        if (chosen == null) chosen = new Candidate(root.getBlockPos(), 1, 1);

        Set<BlockPos> activePositions = new HashSet<>();
        for (int u = 0; u < chosen.width; u++) {
            for (int v = 0; v < chosen.height; v++) {
                activePositions.add(RadarDisplayStructureResolver.at(chosen.origin, facing, u, v));
            }
        }

        boolean geometryChanged = !chosen.origin.equals(oldOrigin)
                || chosen.width != oldWidth || chosen.height != oldHeight;
        boolean compositionChanged = false;
        for (RadarDisplayBlockEntity member : relatedPanels) {
            BlockPos position = member.getBlockPos();
            int u = localHorizontalCoordinate(chosen.origin, position, facing);
            int v = position.getY() - chosen.origin.getY();
            boolean active = member.getBlockState().getValue(RadarDisplayBlock.FACING) == facing
                    && activePositions.contains(position);
            RadarDisplayFrameShape frame = active
                    ? RadarDisplayStructureResolver.frame(u, v, chosen.width, chosen.height)
                    : RadarDisplayFrameShape.SINGLE;
            BlockState state = member.getBlockState();
            if (state.getValue(RadarDisplayBlock.ACTIVE) != active
                    || state.getValue(RadarDisplayBlock.FRAME_SHAPE) != frame) {
                compositionChanged = true;
                break;
            }
        }
        boolean assemblyChanged = geometryChanged || compositionChanged;
        long structureVersion = root.structureVersion() + (assemblyChanged ? 1 : 0);
        if (assemblyChanged) {
            RadarMonitorDiagnostics.assemblyChanged(level, root,
                    oldOrigin, oldWidth, oldHeight, chosen.origin, chosen.width, chosen.height,
                    compositionChanged);
        }
        for (RadarDisplayBlockEntity member : relatedPanels) {
            BlockPos position = member.getBlockPos();
            int u = localHorizontalCoordinate(chosen.origin, position, facing);
            int v = position.getY() - chosen.origin.getY();
            boolean active = member.getBlockState().getValue(RadarDisplayBlock.FACING) == facing
                    && activePositions.contains(position);
            RadarDisplayFrameShape frame = active
                    ? RadarDisplayStructureResolver.frame(u, v, chosen.width, chosen.height)
                    : RadarDisplayFrameShape.SINGLE;
            member.applyAssembly(root.rootPosition(), displayId, networkId,
                    chosen.origin, chosen.width, chosen.height, structureVersion, active, frame);
        }
        if (assemblyChanged) root.invalidateSnapshotForAssemblyChange();
    }

    /** Повышает новую панель, когда старый корень удалён и его чанк уже загружен. */
    static void recoverOrphanedRoot(ServerLevel level, RadarDisplayBlockEntity panel) {
        if (panel.isRoot() || !level.hasChunkAt(panel.rootPosition())) return;
        BlockEntity previousRoot = level.getBlockEntity(panel.rootPosition());
        if (previousRoot instanceof RadarDisplayBlockEntity root && root.isRoot()
                && panel.displayId().equals(root.displayId())) {
            return;
        }
        // При загрузке чанка блок может уже существовать, а его BlockEntity ещё не зарегистрирован.
        // Это временное состояние нельзя принимать за удалённый корень: иначе дочерняя панель
        // повысится сама и позже появятся два корня с одним DisplayId.
        if (previousRoot == null
                && level.getBlockState(panel.rootPosition()).is(RadarDisplayContent.RADAR_DISPLAY_BLOCK.get())) {
            return;
        }

        Direction facing = panel.getBlockState().getValue(RadarDisplayBlock.FACING);
        List<RadarDisplayBlockEntity> survivors = collectMembers(
                level, panel.displayId(), panel.getBlockPos(), facing).stream()
                .sorted(Comparator.comparing(RadarDisplayBlockEntity::getBlockPos,
                        RadarDisplayStructureResolver::compareRootPositions))
                .toList();
        if (survivors.isEmpty()) return;

        RadarDisplayBlockEntity newRoot = survivors.getFirst();
        NetworkId networkId = survivors.stream().map(RadarDisplayBlockEntity::networkId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        newRoot.promoteToRoot(panel.displayId(), networkId, panel.rootPosition(), "orphan_recovery");
        for (RadarDisplayBlockEntity survivor : survivors) {
            survivor.setAssemblyRoot(newRoot.getBlockPos(), panel.displayId(), networkId);
        }
    }

    /** Помечает загруженные панели одной сборки и их корни после изменения соседнего блока. */
    static void markDirtyAround(Level level, BlockPos anchor, UUID displayId) {
        BlockState state = level.getBlockState(anchor);
        Direction facing = state.hasProperty(RadarDisplayBlock.FACING)
                ? state.getValue(RadarDisplayBlock.FACING) : Direction.NORTH;
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                BlockPos position = RadarDisplayStructureResolver.at(anchor, facing, u, v);
                if (!level.hasChunkAt(position)) continue;
                if (level.getBlockEntity(position) instanceof RadarDisplayBlockEntity panel
                        && (displayId == null || displayId.equals(panel.displayId()))) {
                    panel.markAssemblyDirty();
                    panel.setChanged();
                    panel.markRootDirtyIfPresent();
                }
            }
        }
    }

    /** Собирает только уже загруженные панели с совпадающим DisplayId и направлением. */
    static List<RadarDisplayBlockEntity> collectMembers(ServerLevel level, UUID displayId,
                                                          BlockPos anchor, Direction facing) {
        List<RadarDisplayBlockEntity> members = new ArrayList<>();
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                BlockPos position = RadarDisplayStructureResolver.at(anchor, facing, u, v);
                if (!level.hasChunkAt(position)) continue;
                if (level.getBlockEntity(position) instanceof RadarDisplayBlockEntity panel
                        && displayId.equals(panel.displayId())
                        && panel.getBlockState().getValue(RadarDisplayBlock.FACING) == facing) {
                    members.add(panel);
                }
            }
        }
        return members;
    }

    /**
     * Находит панели того же DisplayId в максимально возможном объёме 5×5 сборки независимо
     * от их направления. Обход куба ограничен ±4 блоками и читает только загруженные чанки;
     * это нужно для сброса старой рамки, когда одну панель повернули или сборка была повреждена.
     */
    private static List<RadarDisplayBlockEntity> collectNearbyPanels(ServerLevel level,
                                                                       UUID displayId,
                                                                       BlockPos anchor) {
        List<RadarDisplayBlockEntity> panels = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos position = anchor.offset(dx, dy, dz);
                    if (!level.hasChunkAt(position)) continue;
                    if (level.getBlockEntity(position) instanceof RadarDisplayBlockEntity panel
                            && displayId.equals(panel.displayId())) {
                        panels.add(panel);
                    }
                }
            }
        }
        return panels;
    }

    private static Candidate preserveCurrentRectangle(ServerLevel level, Direction facing, UUID displayId,
                                                        BlockPos origin, int width, int height) {
        if (width < 1 || height < 1
                || width > RadarDisplayStructureResolver.MAX_SIZE
                || height > RadarDisplayStructureResolver.MAX_SIZE) {
            return null;
        }
        for (int u = 0; u < width; u++) {
            for (int v = 0; v < height; v++) {
                BlockPos position = RadarDisplayStructureResolver.at(origin, facing, u, v);
                if (!level.hasChunkAt(position)) continue;
                if (!(level.getBlockEntity(position) instanceof RadarDisplayBlockEntity panel)
                        || !displayId.equals(panel.displayId())
                        || panel.getBlockState().getValue(RadarDisplayBlock.FACING) != facing) {
                    return null;
                }
            }
        }
        // Выгруженная часть действительного прямоугольника остаётся неизвестной, а не сломанной.
        return new Candidate(origin, width, height);
    }

    private static Candidate chooseLargestRectangle(ServerLevel level, Direction facing,
                                                     BlockPos rootPosition,
                                                     List<RadarDisplayBlockEntity> members) {
        Set<BlockPos> positions = new HashSet<>();
        for (RadarDisplayBlockEntity member : members) positions.add(member.getBlockPos());
        List<Candidate> candidates = new ArrayList<>();
        for (int originU = -4; originU <= 0; originU++) {
            for (int originV = -4; originV <= 0; originV++) {
                BlockPos origin = RadarDisplayStructureResolver.at(rootPosition, facing, originU, originV);
                for (int width = 1; width <= RadarDisplayStructureResolver.MAX_SIZE; width++) {
                    if (originU + width <= 0) continue;
                    for (int height = 1; height <= RadarDisplayStructureResolver.MAX_SIZE; height++) {
                        if (originV + height <= 0) continue;
                        boolean complete = true;
                        for (int u = 0; u < width && complete; u++) {
                            for (int v = 0; v < height; v++) {
                                BlockPos position = RadarDisplayStructureResolver.at(origin, facing, u, v);
                                if (!level.hasChunkAt(position) || !positions.contains(position)) {
                                    complete = false;
                                    break;
                                }
                            }
                        }
                        if (complete) candidates.add(new Candidate(origin, width, height));
                    }
                }
            }
        }
        return candidates.stream().max(Comparator
                .comparingInt(Candidate::area)
                .thenComparingInt(candidate -> Math.min(candidate.width, candidate.height))
                .thenComparingInt(Candidate::width)
                .thenComparing(candidate -> candidate.origin,
                        RadarDisplayStructureResolver::compareRootPositions)).orElse(null);
    }

    private static int localHorizontalCoordinate(BlockPos origin, BlockPos position, Direction facing) {
        Direction right = RadarDisplayStructureResolver.right(facing);
        int dx = position.getX() - origin.getX();
        int dz = position.getZ() - origin.getZ();
        return dx * right.getStepX() + dz * right.getStepZ();
    }

    private record Candidate(BlockPos origin, int width, int height) {
        private int area() {
            return width * height;
        }
    }
}
