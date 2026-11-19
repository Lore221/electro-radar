package com.electroradar.radar.bearing;

import java.util.UUID;
import com.electroradar.radar.simple.RadarRotation;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** The only assembly adapter that mutates Minecraft/Create state. */
final class BearingWorld implements BearingAssembly.Port {
    private final RadarBearingBlockEntity owner;
    BearingWorld(RadarBearingBlockEntity owner) { this.owner = owner; }
    private ServerLevel level() { return (ServerLevel) owner.getLevel(); }
    @Override public int discoverSections() {
        ServerLevel level = level();
        int count = 0;
        for (int i = 1; i <= 6; i++) {
            BlockPos pos = owner.getBlockPos().above(i);
            if (!level.hasChunkAt(pos)) return 0;
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof AntennaSectionBlock)) break;
            // All sections must face north so the physical panel and published phase agree.
            if (state.getValue(AntennaSectionBlock.FACING) != Direction.NORTH) return 0;
            count++;
        }
        return count;
    }
    @Override public UUID capture(int count, RadarRotation rotation) {
        ServerLevel level = level();
        AntennaContraption antenna = new AntennaContraption();
        ControlledContraptionEntity entity = null;
        try {
            if (!antenna.captureSections(level, owner.getBlockPos(), count, rotation)) return null;
            entity = ControlledContraptionEntity.create(level, owner, antenna);
            BlockPos anchor = owner.getBlockPos().above();
            entity.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
            entity.setRotationAxis(Direction.Axis.Y);
            entity.setAngle(0);
            // Establish a persistent recovery vehicle before removing any world blocks.
            if (!level.addFreshEntity(entity)) { entity.discard(); return null; }
            antenna.removeBlocksFromWorld(level, BlockPos.ZERO);
            return entity.getUUID();
        } catch (Exception failure) {
            // Partial remove must restore only missing cells; retained entity owns any remainder.
            if (entity != null && entity.isAlive()) {
                antenna.checkpointRemainingSource(level, owner.getBlockPos());
                if (!AntennaContraption.restore(entity, level, owner.getBlockPos())) return entity.getUUID();
            }
            org.slf4j.LoggerFactory.getLogger(BearingWorld.class).warn("Antenna capture rolled back at {}", owner.getBlockPos(), failure);
            return null;
        }
    }
    ControlledContraptionEntity resolve(UUID id) {
        if (id == null) return null;
        var found = level().getEntity(id);
        return found instanceof ControlledContraptionEntity controlled && controlled.isAlive()
                && controlled.getContraption() instanceof AntennaContraption
                && controlled.blockPosition().below().equals(owner.getBlockPos()) ? controlled : null;
    }
    boolean recovering(UUID id) {
        var entity = resolve(id);
        return entity != null && ((AntennaContraption) entity.getContraption()).recoveryOnly();
    }
    @Override public boolean available(UUID id) {
        var entity = resolve(id);
        return entity != null && !((AntennaContraption) entity.getContraption()).recoveryOnly();
    }
    @Override public boolean restore(UUID id) {
        var entity = resolve(id);
        return entity != null && AntennaContraption.restore(entity, level(), owner.getBlockPos());
    }
}
