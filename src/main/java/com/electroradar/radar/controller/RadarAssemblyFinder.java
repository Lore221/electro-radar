package com.electroradar.radar.controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Только читает загруженный мир и описывает одну апертуру. Здесь нет Create entity, изменения
 * BlockState, регистрации сети и сканирования целей; это позволяет сначала завершить все отказы.
 */
final class RadarAssemblyFinder {
    private RadarAssemblyFinder() {
    }

    public static Discovery discover(ServerLevel level, BlockPos controllerPos,
                                     Direction controllerFacing, int maxPanels, int maxOverviewModules) {
        BlockPos first = controllerPos.above();
        if (!level.hasChunkAt(first)) {
            return failed(Failure.UNLOADED_AREA, first, controllerFacing, RadarApertureKind.PANEL);
        }
        BlockState firstState = level.getBlockState(first);
        if (OverviewModuleBlock.isOverviewModule(firstState)) {
            return discoverOverview(level, controllerPos, first, maxOverviewModules);
        }
        if (!RadarPanelBlock.isPanel(firstState)) {
            return failed(Failure.NO_PANEL_ABOVE, first, controllerFacing, RadarApertureKind.PANEL);
        }
        if (firstState.getValue(RadarPanelBlock.FACING) != controllerFacing) {
            return failed(Failure.INCOMPATIBLE_DIRECTION, first, controllerFacing, RadarApertureKind.PANEL);
        }

        Direction right = controllerFacing.getCounterClockWise();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        ArrayList<BlockPos> positions = new ArrayList<>();
        Map<BlockPos, BlockState> states = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        pending.add(first);
        visited.add(first);
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            positions.add(current.immutable());
            states.put(current.immutable(), level.getBlockState(current));
            if (positions.size() == maxPanels + 1) {
                return new Discovery(false, Failure.TOO_MANY_PANELS, RadarApertureKind.PANEL,
                        positions, states, first, controllerFacing);
            }

            for (BlockPos next : List.of(current.above(), current.below(),
                    current.relative(right), current.relative(right.getOpposite()))) {
                // На границе чанка соседний блок может быть панелью. Не загружаем его и не
                // трактуем недоступную часть компоненты как пустую щель.
                if (!level.hasChunkAt(next)) {
                    return new Discovery(false, Failure.UNLOADED_AREA, RadarApertureKind.PANEL,
                            positions, states, next.immutable(), controllerFacing);
                }
                if (!inPanelPlane(first, next, controllerFacing)) continue;
                BlockState nextState = level.getBlockState(next);
                if (OverviewModuleBlock.isOverviewModule(nextState)) {
                    return new Discovery(false, Failure.MIXED_APERTURE, RadarApertureKind.PANEL,
                            positions, states, next.immutable(), controllerFacing);
                }
                if (!RadarPanelBlock.isPanel(nextState)) continue;
                if (nextState.getValue(RadarPanelBlock.FACING) != controllerFacing) {
                    return new Discovery(false, Failure.INCOMPATIBLE_DIRECTION, RadarApertureKind.PANEL,
                            positions, states, next.immutable(), controllerFacing);
                }
                if (next.below().equals(controllerPos)) continue;
                if (level.getBlockState(next.below()).getBlock() instanceof RadarControllerBlock) {
                    return new Discovery(false, Failure.AMBIGUOUS_OWNER, RadarApertureKind.PANEL,
                            positions, states, next.immutable(), controllerFacing);
                }
                if (visited.add(next.immutable())) pending.addLast(next.immutable());
            }
        }

        if (positions.isEmpty()) {
            return failed(Failure.NO_PANEL_ABOVE, first, controllerFacing, RadarApertureKind.PANEL);
        }
        return new Discovery(true, Failure.NONE, RadarApertureKind.PANEL,
                positions, states, first, controllerFacing);
    }

    /** Находит только непрерывный столбик одного X/Z и проверяет лимит дополнительным блоком. */
    private static Discovery discoverOverview(ServerLevel level, BlockPos controllerPos,
                                              BlockPos first, int maxModules) {
        ArrayList<BlockPos> positions = new ArrayList<>();
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int offset = 0; offset <= maxModules; offset++) {
            BlockPos position = first.above(offset).immutable();
            if (!level.hasChunkAt(position)) {
                return new Discovery(false, Failure.UNLOADED_AREA, RadarApertureKind.OVERVIEW,
                        positions, states, position, Direction.NORTH);
            }
            BlockState state = level.getBlockState(position);
            if (!OverviewModuleBlock.isOverviewModule(state)) {
                if (!positions.isEmpty() && RadarPanelBlock.isPanel(state)) {
                    return new Discovery(false, Failure.MIXED_APERTURE, RadarApertureKind.OVERVIEW,
                            positions, states, position, Direction.NORTH);
                }
                break;
            }
            positions.add(position);
            states.put(position, state);
            if (positions.size() > maxModules) {
                return new Discovery(false, Failure.TOO_MANY_OVERVIEW_MODULES, RadarApertureKind.OVERVIEW,
                        positions, states, position, Direction.NORTH);
            }
        }
        if (positions.isEmpty()) {
            return failed(Failure.NO_PANEL_ABOVE, first, Direction.NORTH, RadarApertureKind.OVERVIEW);
        }
        return new Discovery(true, Failure.NONE, RadarApertureKind.OVERVIEW,
                positions, states, controllerPos.above(), Direction.NORTH);
    }

    private static boolean inPanelPlane(BlockPos first, BlockPos position, Direction facing) {
        int depth = (position.getX() - first.getX()) * facing.getStepX()
                + (position.getY() - first.getY()) * facing.getStepY()
                + (position.getZ() - first.getZ()) * facing.getStepZ();
        return depth == 0;
    }

    private static Discovery failed(Failure reason, BlockPos position, Direction facing,
                                    RadarApertureKind kind) {
        return new Discovery(false, reason, kind, List.of(), Map.of(), position, facing);
    }

    public enum Failure {
        NONE(""),
        NO_PANEL_ABOVE("message.electroradar.assembly.no_panel"),
        INCOMPATIBLE_DIRECTION("message.electroradar.assembly.incompatible_direction"),
        TOO_MANY_PANELS("message.electroradar.assembly.too_many_panels"),
        TOO_MANY_OVERVIEW_MODULES("message.electroradar.assembly.too_many_overview_modules"),
        MIXED_APERTURE("message.electroradar.assembly.mixed_aperture"),
        UNLOADED_AREA("message.electroradar.assembly.unloaded_area"),
        AMBIGUOUS_OWNER("message.electroradar.assembly.ambiguous_owner"),
        CREATE_REJECTED("message.electroradar.assembly.create_rejected"),
        ENTITY_REJECTED("message.electroradar.assembly.entity_rejected"),
        ENTITY_UNAVAILABLE("message.electroradar.assembly.entity_unavailable");

        private final String translationKey;

        Failure(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public record Discovery(boolean valid, Failure failure, RadarApertureKind apertureKind,
                            List<BlockPos> positions,
                            Map<BlockPos, BlockState> sourceStates, BlockPos firstPanel,
                            Direction facing) {
        public Discovery {
            positions = positions.stream().map(BlockPos::immutable).toList();
            sourceStates = Map.copyOf(sourceStates);
            firstPanel = firstPanel.immutable();
        }

        public int panelCount() {
            return positions.size();
        }

        public int apertureCount() { return positions.size(); }
    }
}
