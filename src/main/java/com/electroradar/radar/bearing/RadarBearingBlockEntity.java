package com.electroradar.radar.bearing;

import java.util.Optional;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkIdBindingTarget;
import com.electroradar.radar.network.NetworkKind;
import com.electroradar.radar.simple.RadarRotation;
import com.electroradar.radar.simple.SimpleRadarRuntime;
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.visual.RadarVisualProfileSource;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class RadarBearingBlockEntity extends BlockEntity
        implements IControlContraption, NetworkIdBindingTarget, RadarVisualProfileSource {
    private final BearingAssembly assembly = new BearingAssembly();
    private final BearingWorld world = new BearingWorld(this);
    private final SimpleRadarRuntime runtime = new SimpleRadarRuntime(this);
    public RadarBearingBlockEntity(BlockPos pos, BlockState state) { super(RadarBearingContent.BEARING_ENTITY.get(), pos, state); }
    @Override public void onLoad() { super.onLoad(); runtime.onLoad(); }
    void serverTick() {
        if (!(level instanceof ServerLevel server) || isRemoved()) return;
        if (world.recovering(assembly.entityId())) {
            if (assembly.toggle(world, server.getGameTime()) == BearingAssembly.Result.DISASSEMBLED) setChanged();
        }
        runtime.tick(server);
    }
    public void toggleAssembly(Player player) {
        if (!(level instanceof ServerLevel server)) return;
        var result = assembly.toggle(world, server.getGameTime());
        setChanged();
        player.displayClientMessage(Component.translatable("message.electroradar.bearing." + result.name().toLowerCase(java.util.Locale.ROOT)), true);
    }
    public void prepareForBlockRemoval() {
        if (level instanceof ServerLevel && assembly.active()) assembly.toggle(world, level.getGameTime());
        runtime.unregister();
    }
    @Override public void onChunkUnloaded() { runtime.unregister(); super.onChunkUnloaded(); }
    @Override public void setRemoved() { runtime.unregister(); super.setRemoved(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        runtime.write(tag);
        var saved = assembly.save();
        if (saved.entityId() != null) tag.putUUID("AntennaUuid", saved.entityId());
        tag.putInt("AntennaCount", saved.antennaCount());
        tag.putLong("AntennaEpoch", saved.rotation().epochTick());
        tag.putDouble("AntennaAngle", saved.rotation().angleAtEpoch());
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        runtime.read(tag);
        double angle = tag.getDouble("AntennaAngle");
        assembly.load(new BearingAssembly.Saved(tag.hasUUID("AntennaUuid") ? tag.getUUID("AntennaUuid") : null,
                tag.getInt("AntennaCount"), new RadarRotation(tag.getLong("AntennaEpoch"), Double.isFinite(angle) ? angle : 0)));
    }
    public boolean scanningReady() { return level instanceof ServerLevel && !isRemoved() && assembly.scanningReady(world); }
    public int antennaCount() { return assembly.antennaCount(); }
    /** Local north is zero; world gameTime is the clock (the runtime projects Sable rotation). */
    public RadarRotation rotation() { return assembly.rotation(); }
    public boolean hasActiveAssembly() { return assembly.active(); }
    public boolean owns(ControlledContraptionEntity entity) {
        return assembly.owns(entity.getUUID()) && entity.getContraption() instanceof AntennaContraption;
    }
    @Override public boolean isAttachedTo(AbstractContraptionEntity entity) { return entity instanceof ControlledContraptionEntity controlled && owns(controlled); }
    @Override public void attach(ControlledContraptionEntity entity) { /* UUID is the only ownership proof; never adopt an orphan. */ }
    @Override public void onStall() {}
    @Override public boolean isValid() { return !isRemoved() && level != null && getBlockState().getBlock() instanceof RadarBearingBlock; }
    @Override public BlockPos getBlockPosition() { return worldPosition; }
    public NetworkId networkId() { return runtime.networkId(); }
    @Override public NetworkKind networkKindForLinking() { return NetworkKind.SIMPLE_RADAR; }
    @Override public NetworkId getOrCreateNetworkIdForBinding() { return runtime.getOrCreateNetworkId(); }
    @Override public void applyNetworkIdFromItem(NetworkId id) { runtime.applyNetworkId(id); }
    @Override public boolean reconfigureNetwork(NetworkId id) { return runtime.reconfigure(id); }
    @Override public Optional<NetworkId> visualNetworkId() { return Optional.ofNullable(runtime.networkId()); }
    @Override public Optional<RadarVisualProfile> radarVisualProfile() { return runtime.visualProfile(); }
}
