package com.electroradar.radar.alarm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

import com.electroradar.cannon.mount.BigCannonShell;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.EndpointRole;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.TargetAvailability;
import com.electroradar.radar.network.TargetRef;

public final class ShellAlarmBlockEntity extends SmartBlockEntity implements NetworkLinkTarget, IHaveGoggleInformation {
    private static final String NETWORK = "electroradar_network_id";
    private static final String ZONE = "ProtectionZone";
    private final ShellThreatTracker tracker = new ShellThreatTracker();
    private NetworkId networkId;
    private ConsumerConnection connection;
    private ProtectionZone zone;
    private boolean registrationFailureLogged;
    private Status status = Status.NO_NETWORK;
    private int dangerousCount;
    private int unsupportedCount;

    public ShellAlarmBlockEntity(BlockPos pos, BlockState state) {
        super(ShellAlarmContent.SHELL_ALARM_ENTITY.get(), pos, state);
    }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { }
    public ProtectionZone zone() { return zone; }
    /** Серверный неизменяемый набор. UUID не сохраняются в NBT и не рассылаются клиентам. */
    public Set<UUID> dangerousProjectiles() { return tracker.dangerousProjectiles(); }

    public boolean applyZone(ProtectionZone next) {
        if (!(level instanceof ServerLevel world) || !validZone(world, next)) return false;
        if (Objects.equals(zone, next)) return true;
        zone = next;
        clearThreats();
        setChanged();
        sendData();
        return true;
    }

    static boolean validZone(ServerLevel level, ProtectionZone zone) {
        return zone != null && zone.dimension().equals(level.dimension().location())
                && zone.min().getY() >= level.getMinBuildHeight() && zone.max().getY() < level.getMaxBuildHeight();
    }

    void serverTick() {
        tick();
        if (!(level instanceof ServerLevel world)) return;
        ensureConnection(world);
        boolean wasDangerous = dangerousCount > 0;
        Status oldStatus = status;
        int oldCount = dangerousCount, oldUnsupported = unsupportedCount;
        unsupportedCount = 0;
        if (connection == null) {
            tracker.clear();
            status = Status.NO_NETWORK;
        } else if (!validZone(world, zone)) {
            tracker.clear();
            status = Status.NO_ZONE;
        } else {
            List<ShellThreatTracker.Observation> observations = new ArrayList<>();
            Set<UUID> currentThreats = new HashSet<>();
            // Тот же контракт разрешённого чтения, что у Target Controller: только доступные
            // сети обнаружения с AVAILABLE sample, включая её обычную память.
            var targets = connection.queryTargets(view -> new Candidate(view.target(), view.liveResult()));
            for (Candidate candidate : targets.targets()) {
                TargetRef ref = candidate.target();
                if (candidate.sample().availability() != TargetAvailability.AVAILABLE
                        || ref.sourceKind() != TargetRef.SourceKind.ENTITY || !ref.dimension().equals(world.dimension())) continue;
                if (!(world.getEntity(ref.id()) instanceof AbstractBigCannonProjectile projectile)
                        || !projectile.isAlive() || projectile.isInGround()) continue;
                // Уже внутри зоны — известная опасность даже без поддержки физики или
                // свободного бюджета на будущую траекторию.
                if (zone.bounds().inflate(1.0E-9D).intersects(projectile.getBoundingBox())) currentThreats.add(ref.id());
                var observation = BigCannonShell.observe(world, projectile);
                if (observation.isPresent()) {
                    var sample = observation.get();
                    observations.add(new ShellThreatTracker.Observation(projectile.getUUID(), projectile.tickCount,
                            sample.shell(), sample.halfSize()));
                } else unsupportedCount++;
            }
            // Пограничные/изменяющие движение цели не должны постоянно занимать бюджет первыми.
            if (!observations.isEmpty()) Collections.rotate(observations, -(int) (world.getGameTime() % observations.size()));
            tracker.refresh(observations, zone.geometry(), currentThreats);
            status = !dangerousProjectiles().isEmpty() ? Status.DANGER
                    : tracker.incompleteCount() > 0 || unsupportedCount > 0 ? Status.INCOMPLETE : Status.CLEAR;
        }
        dangerousCount = dangerousProjectiles().size();
        if (wasDangerous != (dangerousCount > 0)) updateRedstone(world);
        if (status != oldStatus || oldCount != dangerousCount || oldUnsupported != unsupportedCount) sendData();
    }

    @Override public NetworkId networkIdForLinking() { return networkId; }
    @Override public boolean reconfigureNetwork(NetworkId next) {
        if (Objects.equals(networkId, next)) return true;
        closeConnection();
        networkId = next;
        clearThreats();
        setChanged();
        sendData();
        return true;
    }

    private void ensureConnection(ServerLevel world) {
        if (networkId == null) { closeConnection(); return; }
        if (connection != null && connection.isOpen()) return;
        closeConnection();
        EndpointKey endpoint = new EndpointKey.Static(world.dimension(), worldPosition);
        try {
            connection = new ConsumerConnection(world.getServer(), endpoint, networkId, EndpointRole.CONSUMER);
            registrationFailureLogged = false;
        } catch (RuntimeException failure) {
            if (!registrationFailureLogged) RadarDiagnostics.logException("shell_alarm_registration_failed", networkId,
                    endpoint, null, world.getGameTime(), null, failure);
            registrationFailureLogged = true;
        }
    }

    private void clearThreats() {
        boolean active = resetThreats();
        if (active && level instanceof ServerLevel world) updateRedstone(world);
    }

    /** @return был ли сигнал включён */
    private boolean resetThreats() {
        boolean active = dangerousCount > 0;
        tracker.clear();
        dangerousCount = 0;
        unsupportedCount = 0;
        status = networkId == null ? Status.NO_NETWORK : zone == null ? Status.NO_ZONE : Status.CLEAR;
        return active;
    }

    /** Сигнал сильный на все стороны: обновляются и соседи, и блоки, которые он питает через них. */
    private void updateRedstone(ServerLevel world) {
        Block block = getBlockState().getBlock();
        world.updateNeighborsAt(worldPosition, block);
        for (Direction direction : Direction.values()) world.updateNeighborsAt(worldPosition.relative(direction), block);
    }

    private void closeConnection() {
        ConsumerConnection previous = connection;
        connection = null;
        if (previous != null) previous.close();
    }

    /** Create вызывает и при выгрузке чанка: оповещать соседей здесь нельзя, это может подгрузить чанк. */
    @Override public void invalidate() {
        closeConnection();
        resetThreats();
        super.invalidate();
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (networkId != null) tag.putUUID(NETWORK, networkId.value());
        if (zone != null) tag.put(ZONE, zone.save());
        if (clientPacket) {
            tag.putString("AlarmStatus", status.name());
            tag.putInt("DangerousCount", dangerousCount);
            tag.putInt("UnsupportedCount", unsupportedCount);
        }
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        NetworkId saved = tag.hasUUID(NETWORK) ? new NetworkId(tag.getUUID(NETWORK)) : null;
        ProtectionZone savedZone = ProtectionZone.load(tag.getCompound(ZONE)).orElse(null);
        if (!Objects.equals(saved, networkId) || !Objects.equals(savedZone, zone)) {
            closeConnection();
            tracker.clear();
            networkId = saved;
            zone = savedZone;
        }
        if (clientPacket) {
            try { status = Status.valueOf(tag.getString("AlarmStatus")); }
            catch (IllegalArgumentException invalid) { status = Status.NO_NETWORK; }
            dangerousCount = Math.max(0, tag.getInt("DangerousCount"));
            unsupportedCount = Math.max(0, tag.getInt("UnsupportedCount"));
        }
    }

    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.electroradar.shell_alarm")));
        tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.shell_alarm.status."
                + status.name().toLowerCase(java.util.Locale.ROOT)).withStyle(status == Status.DANGER ? ChatFormatting.RED : ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.shell_alarm.count", dangerousCount)));
        if (zone != null) tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.zone.size",
                zone.width(), zone.height(), zone.depth()).withStyle(ChatFormatting.AQUA)));
        return true;
    }

    private record Candidate(TargetRef target, LiveResult sample) { }
    private enum Status { NO_NETWORK, NO_ZONE, CLEAR, DANGER, INCOMPLETE }
}
