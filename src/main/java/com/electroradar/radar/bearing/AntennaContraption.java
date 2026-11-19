package com.electroradar.radar.bearing;

import java.util.LinkedHashMap;
import java.util.Map;
import com.electroradar.radar.simple.RadarRotation;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Native Create bearing with an explicitly bounded, antenna-only capture. */
public final class AntennaContraption extends BearingContraption {
    private RadarRotation rotation = new RadarRotation(0, 0);
    // Cells already placed by a partial restoration are checkpointed with the entity.
    private boolean recoveryOnly;
    private final java.util.Set<BlockPos> restored = new java.util.HashSet<>();
    public AntennaContraption() { super(false, Direction.UP); }
    @Override public ContraptionType getType() { return RadarBearingContent.ANTENNA_CONTRAPTION.get(); }
    @Override public boolean assemble(Level level, BlockPos position) { return false; }
    @Override public boolean canBeStabilized(Direction direction, BlockPos pos) { return false; }

    boolean captureSections(ServerLevel level, BlockPos bearing, int count, RadarRotation phase) throws Exception {
        anchor = bearing.above();
        bounds = new AABB(BlockPos.ZERO);
        rotation = phase;
        // Validate the whole capture before touching any source block; glue is deliberately ignored.
        for (int i = 1; i <= count; i++) {
            BlockPos pos = bearing.above(i);
            BlockState state = level.getBlockState(pos);
            if (!level.hasChunkAt(pos) || !(state.getBlock() instanceof AntennaSectionBlock)
                    || !BlockMovementChecks.isMovementAllowed(state, level, pos)
                    || !BlockMovementChecks.isMovementNecessary(state, level, pos)) return false;
        }
        for (int i = 1; i <= count; i++) {
            BlockPos pos = bearing.above(i);
            addBlock(level, pos, capture(level, pos));
        }
        startMoving(level);
        expandBoundsAroundAxis(Direction.Axis.Y);
        return blocks.size() == count;
    }
    @Override public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
        CompoundTag tag = super.writeNBT(registries, spawnPacket);
        tag.putBoolean("RecoveryOnly", recoveryOnly);
        tag.putLong("RadarEpoch", rotation.epochTick());
        tag.putDouble("RadarAngle", rotation.angleAtEpoch());
        tag.putLongArray("RestoredCells", restored.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }
    @Override public void readNBT(Level level, CompoundTag tag, boolean spawnData) {
        super.readNBT(level, tag, spawnData);
        recoveryOnly = tag.getBoolean("RecoveryOnly");
        restored.clear();
        for (long cell : tag.getLongArray("RestoredCells")) restored.add(BlockPos.of(cell));
        double angle = tag.getDouble("RadarAngle");
        rotation = new RadarRotation(tag.getLong("RadarEpoch"), Double.isFinite(angle) ? angle : 0);
    }
    /** Positive Create Y rotates north towards west; our clockwise bearing uses negative Y. */
    void applyPhase(ControlledContraptionEntity entity, double tick) {
        entity.setAngle((float) -RadarRotation.normalize(rotation.angleDegrees(tick) - rotation.angleAtEpoch()));
    }
    boolean recoveryOnly() { return recoveryOnly; }
    void checkpointRemainingSource(ServerLevel level, BlockPos bearing) {
        recoveryOnly = true;
        sourceStates(bearing).forEach((position, state) -> {
            if (level.getBlockState(position).equals(state)) restored.add(position.subtract(bearing.above()));
        });
    }
    Map<BlockPos, BlockState> sourceStates(BlockPos bearing) {
        Map<BlockPos, BlockState> source = new LinkedHashMap<>();
        blocks.forEach((local, info) -> source.put(bearing.above().offset(local), info.state()));
        return source;
    }
    /** Never let Create overwrite an occupied restoration cell or drop a captured section. */
    static boolean restore(ControlledContraptionEntity entity, ServerLevel level, BlockPos bearing) {
        if (!(entity.getContraption() instanceof AntennaContraption antenna)) return false;
        Map<BlockPos, BlockState> source = new LinkedHashMap<>();
        antenna.blocks.forEach((local, info) -> source.put(local, info.state()));
        BlockPos anchor = bearing.above();
        boolean restored = BearingRestoration.restore(source, antenna.restored,
                new BearingRestoration.World<BlockPos, BlockState>() {
                    @Override public boolean loaded(BlockPos local) { return level.hasChunkAt(anchor.offset(local)); }
                    @Override public BlockState state(BlockPos local) { return level.getBlockState(anchor.offset(local)); }
                    @Override public boolean empty(BlockState state) { return state.isAir(); }
                    @Override public boolean place(BlockPos local, BlockState state) {
                        return level.setBlock(anchor.offset(local), state, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                    }
                });
        // A transferred section is no longer entity-owned, even if a player removes it before
        // the next retry. Exclude it from the saved payload and stop scanning a partial aperture.
        antenna.restored.forEach(antenna.blocks::remove);
        if (!restored && !antenna.restored.isEmpty()) antenna.recoveryOnly = true;
        if (restored) entity.discard();
        return restored;
    }
    /** Client interpolation runs from this game tick to the next, using the saved server epoch. */
    public static void onEntityTickEnd(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof ControlledContraptionEntity entity && entity.level().isClientSide
                && entity.getContraption() instanceof AntennaContraption antenna) {
            antenna.applyPhase(entity, entity.level().getGameTime() + 1.0);
        }
    }
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ControlledContraptionEntity entity)
                || !(entity.getContraption() instanceof AntennaContraption antenna) || !entity.isAlive()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        antenna.applyPhase(entity, level.getGameTime());
        BlockPos bearing = entity.blockPosition().below();
        antenna.anchor = bearing.above();
        if (!level.hasChunkAt(bearing)) { event.setCanceled(true); return; }
        if (level.getBlockEntity(bearing) instanceof RadarBearingBlockEntity owner && owner.owns(entity)) return;
        // Create discards an orphan on its normal tick. Keep the saved entity until every cell
        // is free and every antenna section has been restored, including delayed chunk loading.
        event.setCanceled(true);
        restore(entity, level, bearing);
    }
}
