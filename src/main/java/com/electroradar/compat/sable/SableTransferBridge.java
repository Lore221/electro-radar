package com.electroradar.compat.sable;

import java.util.*;
import com.electroradar.radar.controller.RadarControllerBlockEntity;
import com.electroradar.radar.monitor.*;
import com.electroradar.radar.spatial.InstrumentTransferScope;
import com.electroradar.radar.spatial.RadarSpatial;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stages identities before Sable copies NBT; reconciles only actual successful destination BEs. */
public final class SableTransferBridge {
    private static final String NONCE = "electroradar_transfer_nonce";
    private SableTransferBridge() { }
    @FunctionalInterface public interface Move { void run(Iterable<BlockPos> positions); }
    private record Group(UUID display, BlockPos root) { }
    private record Stage(BlockEntity source, BlockPos destination, UUID nonce, Group group) { }

    public static void move(ServerLevel source, SubLevelAssemblyHelper.AssemblyTransform transform,
                            Iterable<BlockPos> original, Move operation) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        original.forEach(p -> positions.add(p.immutable()));
        for (BlockPos position : positions) {
            if (source.getBlockEntity(position) instanceof RadarControllerBlockEntity radar && !radar.canTransferStructure())
                throw new SableTransferRejected("Radar aperture must finish loading before structure transfer: " + position);
        }
        Map<Group, List<RadarDisplayBlockEntity>> oldGroups = new HashMap<>();
        // Restore owned apertures before any blocks move, including direct Sable API assembly.
        for (BlockPos position : List.copyOf(positions)) {
            BlockEntity be = source.getBlockEntity(position);
            if (be instanceof RadarControllerBlockEntity radar) positions.addAll(radar.prepareForStructureTransfer());
            if (be instanceof RadarDisplayBlockEntity display && display.displayId() != null) {
                Group group = new Group(display.displayId(), display.rootPosition());
                oldGroups.computeIfAbsent(group, ignored -> display.transferMembers());
            }
        }
        for (var group : oldGroups.values()) for (var panel : group) panel.prepareForStructureTransfer();
        List<Stage> stages = new ArrayList<>();
        for (BlockPos position : positions) {
            BlockEntity be = source.getBlockEntity(position);
            if (!(be instanceof RadarControllerBlockEntity) && !(be instanceof RadarDisplayBlockEntity)) continue;
            UUID nonce = UUID.randomUUID();
            be.getPersistentData().putUUID(NONCE, nonce);
            Group group = be instanceof RadarDisplayBlockEntity display && display.displayId() != null
                    ? new Group(display.displayId(), display.rootPosition()) : null;
            stages.add(new Stage(be, transform.apply(position), nonce, group));
        }
        try (var scope = new InstrumentTransferScope(source, positions)) {
            operation.run(List.copyOf(positions));
        } finally {
            ServerLevel destination = transform.getLevel();
            Map<Group, List<RadarDisplayBlockEntity>> newGroups = new HashMap<>();
            for (Stage stage : stages) {
                BlockEntity moved = destination.getBlockEntity(stage.destination());
                if (moved != null && moved.getPersistentData().hasUUID(NONCE)
                        && stage.nonce().equals(moved.getPersistentData().getUUID(NONCE))) {
                    moved.getPersistentData().remove(NONCE);
                    if (moved instanceof RadarDisplayBlockEntity panel && stage.group() != null)
                        newGroups.computeIfAbsent(stage.group(), ignored -> new ArrayList<>()).add(panel);
                    moved.setChanged();
                }
                stage.source().getPersistentData().remove(NONCE);
            }
            for (var group : newGroups.entrySet()) {
                reconcile(group.getValue(), transform.apply(group.getKey().root()), group.getKey().display());
            }
            for (var group : oldGroups.entrySet()) {
                List<RadarDisplayBlockEntity> surviving = group.getValue().stream()
                        .filter(panel -> !panel.isRemoved() && source.getBlockEntity(panel.getBlockPos()) == panel).toList();
                reconcile(surviving, group.getKey().root(), group.getKey().display());
            }
        }
    }

    private static void reconcile(List<RadarDisplayBlockEntity> panels, BlockPos preferredRoot, UUID oldIdentity) {
        Map<BlockPos, RadarDisplayBlockEntity> remaining = new HashMap<>();
        for (var panel : panels) remaining.put(panel.getBlockPos(), panel);
        while (!remaining.isEmpty()) {
            RadarDisplayBlockEntity first = remaining.values().iterator().next();
            List<RadarDisplayBlockEntity> component = new ArrayList<>();
            Deque<RadarDisplayBlockEntity> frontier = new ArrayDeque<>();
            frontier.add(first); remaining.remove(first.getBlockPos());
            Direction facing = first.getBlockState().getValue(RadarDisplayBlock.FACING);
            Direction right = RadarDisplayStructureResolver.right(facing);
            while (!frontier.isEmpty()) {
                var panel = frontier.removeFirst(); component.add(panel);
                for (Direction direction : List.of(right, right.getOpposite(), Direction.UP, Direction.DOWN)) {
                    var next = remaining.get(panel.getBlockPos().relative(direction));
                    if (next != null && next.getBlockState().getValue(RadarDisplayBlock.FACING) == facing
                            && RadarSpatial.sameSpace(panel.getLevel(), panel.getBlockPos(), next.getBlockPos())) {
                        remaining.remove(next.getBlockPos()); frontier.add(next);
                    }
                }
            }
            var preferred = component.stream().filter(panel -> panel.getBlockPos().equals(preferredRoot)).findFirst();
            RadarDisplayBlockEntity root = preferred.orElseGet(() -> component.stream().min(Comparator.comparing(
                    RadarDisplayBlockEntity::getBlockPos, RadarDisplayStructureResolver::compareRootPositions)).orElseThrow());
            UUID identity = preferred.isPresent() ? oldIdentity : UUID.randomUUID();
            for (var panel : component) panel.finishStructureTransfer(root.getBlockPos(), identity);
        }
    }
}
