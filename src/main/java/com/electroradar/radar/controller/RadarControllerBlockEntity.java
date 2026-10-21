package com.electroradar.radar.controller;

import java.util.Optional;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkIdBindingTarget;
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.visual.RadarVisualProfileSource;

/**
 * Координатор установленного радара. Create capture, геометрия, сеть и сканирование делегируются
 * отдельным runtime-компонентам; все мироизменяющие действия идут на основном серверном потоке.
 */
public final class RadarControllerBlockEntity extends BlockEntity
        implements RadarVisualProfileSource, NetworkIdBindingTarget, IControlContraption {
    private final RadarServerRuntime runtime = new RadarServerRuntime();
    private final RadarAssemblyController assembly = new RadarAssemblyController();

    public RadarControllerBlockEntity(BlockPos pos, BlockState state) {
        super(RadarControllerContent.CONTROLLER_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        runtime.onLoad();
        assembly.onLoad(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        runtime.write(tag);
        assembly.write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        runtime.read(tag);
        assembly.read(tag);
    }

    @Override
    public void onChunkUnloaded() {
        runtime.unregister("chunk_unloaded", this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        runtime.unregister("block_entity_removed", this);
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  RadarControllerBlockEntity controller) {
        if (!(level instanceof ServerLevel serverLevel) || controller.isRemoved()) return;
        controller.assembly.tick(controller, serverLevel);
        controller.runtime.tick(controller, serverLevel, controller.mode(),
                controller.assembly.panelCount(), controller.assembly.isAssembled());
    }

    public void toggleAssembly(net.minecraft.world.entity.player.Player player) {
        if (level instanceof ServerLevel serverLevel) assembly.toggleAssembly(this, serverLevel, player);
    }

    public void prepareForBlockRemoval() {
        if (level instanceof ServerLevel serverLevel) {
            assembly.prepareForBlockRemoval(this, serverLevel);
            runtime.unregister("controller_removed", this);
        }
    }

    public RadarMode mode() {
        return getBlockState().getBlock() instanceof RadarControllerBlock block
                ? block.mode() : RadarMode.GROUND;
    }

    /** Созданная или ещё восстанавливаемая Create-сборка принадлежит этому неподвижному BE. */
    public boolean hasActiveAssembly() {
        return assembly.hasActiveAssembly();
    }

    RadarServerRuntime runtime() {
        return runtime;
    }

    RadarAssemblyController assembly() {
        return assembly;
    }

    public NetworkId networkId() {
        return runtime.networkId();
    }

    public EndpointKey.Static endpoint() {
        return runtime.endpoint();
    }

    public long currentTickForDiagnostics() {
        return level instanceof ServerLevel serverLevel ? runtime.currentTick(serverLevel) : -1L;
    }

    @Override
    public NetworkId getOrCreateNetworkIdForBinding() {
        return runtime.getOrCreateNetworkIdForBinding(this);
    }

    @Override
    public void applyNetworkIdFromItem(NetworkId networkId) {
        runtime.applyNetworkIdFromItem(this, networkId);
    }

    @Override
    public Optional<NetworkId> visualNetworkId() {
        return Optional.ofNullable(runtime.networkId());
    }

    @Override
    public Optional<RadarVisualProfile> radarVisualProfile() {
        return runtime.visualProfile(this);
    }

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity contraption) {
        return assembly.isAttachedTo(contraption);
    }

    @Override
    public void attach(ControlledContraptionEntity contraption) {
        assembly.attach(this, contraption);
    }

    @Override
    public void onStall() {
        // Панели не получают кинетическую нагрузку; этот Create callback намеренно ничего не меняет.
    }

    @Override
    public boolean isValid() {
        return !isRemoved() && level != null
                && level.getBlockState(worldPosition).getBlock() instanceof RadarControllerBlock;
    }

    @Override
    public BlockPos getBlockPosition() {
        return worldPosition;
    }

    /** Новому наблюдателю передаём текущую фазу после spawn data Create, не посылая тиковый поток. */
    public static void onEntityStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof ControlledContraptionEntity contraption)
                || !(contraption.getContraption() instanceof RadarPanelContraption)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos controllerPos = contraption.blockPosition().below();
        if (!level.hasChunkAt(controllerPos)
                || !(level.getBlockEntity(controllerPos) instanceof RadarControllerBlockEntity controller)) {
            return;
        }
        controller.assembly.sendCurrentTiltTo(player, contraption);
    }

}
