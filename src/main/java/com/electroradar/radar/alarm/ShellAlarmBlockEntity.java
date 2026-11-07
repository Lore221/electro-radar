package com.electroradar.radar.alarm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import com.electroradar.radar.network.InterceptionNetworks;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.TargetSources;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

import com.electroradar.cannon.mount.BigCannonShell;
import com.electroradar.radar.network.ConsumerConnectionLifecycle;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.TargetAvailability;
import com.electroradar.radar.network.TargetRef;

public final class ShellAlarmBlockEntity extends SmartBlockEntity implements NetworkLinkTarget, IHaveGoggleInformation {
    private static final String NETWORK = "electroradar_network_id";
    private static final String ZONE = "ProtectionZone";
    private final ShellThreatTracker tracker = new ShellThreatTracker();
    private final ShellAlarmDiagnostics diagnostics = new ShellAlarmDiagnostics();
    private NetworkId networkId;
    private final ConsumerConnectionLifecycle connection =
            new ConsumerConnectionLifecycle("shell_alarm_registration_failed");
    private ProtectionZone zone;
    private NetworkId interceptionNetwork;
    private final ProtectionZoneSlot selector = new ProtectionZoneSlot();

    public NetworkId interceptionNetworkId() { return interceptionNetwork; }

    public NetworkId getOrCreateInterceptionNetwork() {
        if (interceptionNetwork == null) { interceptionNetwork = NetworkId.create(); setChanged(); }
        if (level instanceof ServerLevel world) {
            var directory = InterceptionNetworks.forServer(world.getServer());
            var self = new EndpointKey.Static(world.dimension(), worldPosition);
            // Сетью уже владеет другой Alarm: этот блок — копия с тем же NBT, ему нужна своя сеть.
            if (!directory.registerOwner(interceptionNetwork, self)) {
                interceptionNetwork = NetworkId.create();
                directory.registerOwner(interceptionNetwork, self);
                setChanged();
            }
        }
        return interceptionNetwork;
    }

    public boolean insertSelector(ItemStack stack) {
        if (!(level instanceof ServerLevel world) || !(stack.getItem() instanceof ZoneSelectionItem)) return false;
        ProtectionZone selected = ZoneSelectionItem.zone(stack).orElse(null);
        if (!validZone(world, selected) || !selector.insert(stack, selected)) return false;
        zone = selector.zone();
        clearThreats(); setChanged(); sendData();
        return true;
    }

    public ItemStack extractSelector() {
        ItemStack result = selector.extract();
        zone = null;
        clearThreats(); setChanged(); sendData();
        return result;
    }

    /** Проверка прав выполняется сейчас, даже если Alarm ещё не тикал в этом серверном тике. */
    public boolean permits(TargetRef target) {
        if (!(level instanceof ServerLevel world) || !validZone(world, zone)
                || connection.current() == null || !connection.current().isOpen()) return false;
        TargetSources sources = connection.current().sourcesForTarget(target);
        return sources.confirmed() && sources.liveResult()
                .map(LiveResult::availability).filter(TargetAvailability.AVAILABLE::equals).isPresent();
    }
    private Status status = Status.NO_NETWORK;
    private int dangerousCount;
    private int unsupportedCount;
    private int networkTargetCount;
    private int bigShellCount;
    private int supportedShellCount;
    /** Клиент получает разрешение на диагностическую подсказку от серверной JVM. */
    private boolean diagnosticDisplay;

    public ShellAlarmBlockEntity(BlockPos pos, BlockState state) {
        super(ShellAlarmContent.SHELL_ALARM_ENTITY.get(), pos, state);
    }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { }
    public ProtectionZone zone() { return zone; }
    /** Серверный неизменяемый набор. UUID не сохраняются в NBT и не рассылаются клиентам. */
    public Set<UUID> dangerousProjectiles() { return tracker.dangerousProjectiles(); }
    /** Те же снаряды с тиками до входа в зону; по ним контроллер перехвата выбирает самую срочную цель. */
    public Map<UUID, Integer> threats() { return tracker.ticksToDanger(); }

    static boolean validZone(ServerLevel level, ProtectionZone zone) {
        return zone != null && zone.dimension().equals(level.dimension().location())
                && zone.min().getY() >= level.getMinBuildHeight() && zone.max().getY() < level.getMaxBuildHeight();
    }

    void serverTick() {
        tick();
        if (!(level instanceof ServerLevel world)) return;
        getOrCreateInterceptionNetwork();
        connection.ensure(world, worldPosition, networkId);
        diagnostics.begin(world, worldPosition, networkId, zone);
        boolean wasDangerous = dangerousCount > 0;
        Status oldStatus = status;
        int oldCount = dangerousCount, oldUnsupported = unsupportedCount;
        int oldTargets = networkTargetCount, oldBig = bigShellCount, oldSupported = supportedShellCount;
        int available = 0, resolved = 0, current = 0;
        int oldPredictions = tracker.predictionCount();
        networkTargetCount = bigShellCount = supportedShellCount = 0;
        unsupportedCount = 0;
        if (connection.current() == null) {
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
            var targets = connection.current().queryTargets(view -> new Candidate(view.target(), view.liveResult()));
            networkTargetCount = ShellAlarmDiagnostics.ENABLED ? targets.targets().size() : 0;
            for (Candidate candidate : targets.targets()) {
                TargetRef ref = candidate.target();
                if (candidate.sample().availability() != TargetAvailability.AVAILABLE) {
                    diagnostics.target(ref, candidate.sample(), null, "UNAVAILABLE_SAMPLE");
                    continue;
                }
                if (ShellAlarmDiagnostics.ENABLED) available++;
                if (ref.sourceKind() != TargetRef.SourceKind.ENTITY || !ref.dimension().equals(world.dimension())) {
                    diagnostics.target(ref, candidate.sample(), null, "WRONG_SOURCE_OR_DIMENSION");
                    continue;
                }
                Entity entity = world.getEntity(ref.id());
                if (entity == null) {
                    diagnostics.target(ref, candidate.sample(), null, "ENTITY_MISSING");
                    continue;
                }
                if (ShellAlarmDiagnostics.ENABLED) resolved++;
                if (!(entity instanceof AbstractBigCannonProjectile projectile)) {
                    diagnostics.target(ref, candidate.sample(), entity, "NOT_BIG_CANNON");
                    continue;
                }
                if (ShellAlarmDiagnostics.ENABLED) bigShellCount++;
                if (!projectile.isAlive() || projectile.isInGround()) {
                    diagnostics.target(ref, candidate.sample(), projectile, projectile.isAlive() ? "IN_GROUND" : "DEAD");
                    continue;
                }
                // Уже внутри зоны — известная опасность даже без поддержки физики или
                // свободного бюджета на будущую траекторию.
                if (zone.bounds().inflate(1.0E-9D).intersects(projectile.getBoundingBox())) currentThreats.add(ref.id());
                var inspection = BigCannonShell.inspect(world, projectile);
                diagnostics.target(ref, candidate.sample(), projectile, inspection.reason());
                var observation = inspection.observation();
                if (observation.isPresent()) {
                    if (ShellAlarmDiagnostics.ENABLED) supportedShellCount++;
                    var sample = observation.get();
                    observations.add(new ShellThreatTracker.Observation(projectile.getUUID(), projectile.tickCount,
                            sample.shell(), sample.halfSize()));
                } else unsupportedCount++;
            }
            // Пограничные/изменяющие движение цели не должны постоянно занимать бюджет первыми.
            if (!observations.isEmpty()) Collections.rotate(observations, -(int) (world.getGameTime() % observations.size()));
            current = currentThreats.size();
            tracker.refresh(observations, zone.geometry(), currentThreats,
                    ShellAlarmDiagnostics.ENABLED ? diagnostics::prediction : null);
            status = !dangerousProjectiles().isEmpty() ? Status.DANGER
                    : tracker.incompleteCount() > 0 || unsupportedCount > 0 ? Status.INCOMPLETE : Status.CLEAR;
        }
        dangerousCount = dangerousProjectiles().size();
        diagnostics.summary(status.name(), networkTargetCount, available, resolved, bigShellCount, supportedShellCount,
                current, dangerousCount, unsupportedCount, tracker.incompleteCount(), tracker.predictionCount() - oldPredictions);
        if (wasDangerous != (dangerousCount > 0)) world.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        if (status != oldStatus || oldCount != dangerousCount || oldUnsupported != unsupportedCount
                || oldTargets != networkTargetCount || oldBig != bigShellCount || oldSupported != supportedShellCount) sendData();
    }

    @Override public NetworkId networkIdForLinking() { return networkId; }
    @Override public boolean reconfigureNetwork(NetworkId next) {
        if (Objects.equals(networkId, next)) return true;
        connection.close();
        networkId = next;
        clearThreats();
        setChanged();
        sendData();
        return true;
    }

    private void clearThreats() {
        boolean active = resetThreats();
        if (active && level instanceof ServerLevel world) world.updateNeighborsAt(worldPosition, getBlockState().getBlock());
    }

    /** @return был ли сигнал включён */
    private boolean resetThreats() {
        boolean active = dangerousCount > 0;
        tracker.clear();
        dangerousCount = 0;
        unsupportedCount = 0;
        networkTargetCount = bigShellCount = supportedShellCount = 0;
        status = networkId == null ? Status.NO_NETWORK : zone == null ? Status.NO_ZONE : Status.CLEAR;
        return active;
    }

    /** Create вызывает и при выгрузке чанка: оповещать соседей здесь нельзя, это может подгрузить чанк. */
    @Override public void invalidate() {
        if (level instanceof ServerLevel world && interceptionNetwork != null)
            InterceptionNetworks.forServer(world.getServer()).removeOwner(interceptionNetwork,
                    new EndpointKey.Static(world.dimension(), worldPosition));
        connection.close();
        resetThreats();
        super.invalidate();
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (networkId != null) tag.putUUID(NETWORK, networkId.value());
        if (interceptionNetwork != null) tag.putUUID("InterceptionNetwork", interceptionNetwork.value());
        selector.write(tag, registries);
        if (clientPacket && zone != null) tag.put(ZONE, zone.save());
        if (clientPacket) {
            tag.putString("AlarmStatus", status.name());
            tag.putInt("DangerousCount", dangerousCount);
            tag.putInt("UnsupportedCount", unsupportedCount);
            if (ShellAlarmDiagnostics.ENABLED) {
                tag.putBoolean("AlarmDiagnostics", true);
                tag.putInt("NetworkTargetCount", networkTargetCount);
                tag.putInt("BigShellCount", bigShellCount);
                tag.putInt("SupportedShellCount", supportedShellCount);
            }
        }
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        NetworkId saved = tag.hasUUID(NETWORK) ? new NetworkId(tag.getUUID(NETWORK)) : null;
        if (!clientPacket) {
            selector.read(tag, registries);
            interceptionNetwork = tag.hasUUID("InterceptionNetwork")
                    ? new NetworkId(tag.getUUID("InterceptionNetwork")) : null;
        }
        ProtectionZone savedZone = clientPacket ? ProtectionZone.load(tag.getCompound(ZONE)).orElse(null) : selector.zone();
        if (!Objects.equals(saved, networkId) || !Objects.equals(savedZone, zone)) {
            connection.close();
            tracker.clear();
            networkId = saved;
            zone = savedZone;
        }
        if (clientPacket) {
            try { status = Status.valueOf(tag.getString("AlarmStatus")); }
            catch (IllegalArgumentException invalid) { status = Status.NO_NETWORK; }
            dangerousCount = Math.max(0, tag.getInt("DangerousCount"));
            unsupportedCount = Math.max(0, tag.getInt("UnsupportedCount"));
            diagnosticDisplay = tag.getBoolean("AlarmDiagnostics");
            networkTargetCount = diagnosticDisplay ? Math.max(0, tag.getInt("NetworkTargetCount")) : 0;
            bigShellCount = diagnosticDisplay ? Math.max(0, tag.getInt("BigShellCount")) : 0;
            supportedShellCount = diagnosticDisplay ? Math.max(0, tag.getInt("SupportedShellCount")) : 0;
        }
    }

    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.electroradar.shell_alarm")));
        tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.shell_alarm.status."
                + status.name().toLowerCase(java.util.Locale.ROOT)).withStyle(status == Status.DANGER ? ChatFormatting.RED : ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.shell_alarm.count", dangerousCount)));
        if (sneaking && diagnosticDisplay) tooltip.add(Component.literal("    ").append(Component.translatable(
                "message.electroradar.shell_alarm.input_counts", networkTargetCount, bigShellCount, supportedShellCount)
                .withStyle(ChatFormatting.GRAY)));
        if (zone != null) tooltip.add(Component.literal("    ").append(Component.translatable("message.electroradar.zone.size",
                zone.width(), zone.height(), zone.depth()).withStyle(ChatFormatting.AQUA)));
        return true;
    }

    private record Candidate(TargetRef target, LiveResult sample) { }
    private enum Status { NO_NETWORK, NO_ZONE, CLEAR, DANGER, INCOMPLETE }
}
