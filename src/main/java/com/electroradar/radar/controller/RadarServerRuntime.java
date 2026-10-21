package com.electroradar.radar.controller;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkPolicy;
import com.electroradar.radar.network.RadarDetection;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.RegistrationToken;
import com.electroradar.radar.network.ScanWindow;
import com.electroradar.radar.scan.EntityRadarScanner;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Runtime одного установленного источника. Здесь живут NetworkId, регистрационный token и epoch;
 * ни список целей, ни собственный кэш наблюдений не покидают общий EntityRadarScanner/NetworkManager.
 */
public final class RadarServerRuntime {
    private static final String NETWORK_ID_TAG = "NetworkId";
    private static final long PERIOD_TICKS = 40L;
    private static final long FORGET_AFTER_MISSES = 3L;
    private static final int PRE_BOUNDARY_TICKS = 5;
    private static final NetworkPolicy POLICY = new NetworkPolicy(PERIOD_TICKS, FORGET_AFTER_MISSES);
    private static final Predicate<Entity> ELIGIBLE = entity -> !(entity instanceof ItemEntity);
    private static final EntityRadarScanner SCANNER = new EntityRadarScanner();

    private NetworkId networkId;
    private boolean loadedNetworkId;
    private RegistrationToken token;
    private EndpointKey.Static endpoint;
    private Long activeEpoch;
    private Long completedEpoch;
    private Long failureLoggedEpoch;
    private RadarServerConfig.Snapshot activeParameters;
    private RadarAreaGeometry.GeometrySnapshot cachedGeometry;
    private long observedConfigRevision = Long.MIN_VALUE;
    private RadarMode cachedMode;
    private DirectionAndPosition cachedPlacement;
    private boolean registrationFailureLogged;
    private boolean noScheduleLogged;
    private long lastLimitWarningRevision = Long.MIN_VALUE;

    public void read(CompoundTag tag) {
        if (tag.hasUUID(NETWORK_ID_TAG)) {
            networkId = new NetworkId(tag.getUUID(NETWORK_ID_TAG));
            loadedNetworkId = true;
        } else {
            networkId = null;
            loadedNetworkId = false;
        }
        resetTransientState();
    }

    public void write(CompoundTag tag) {
        if (networkId != null) tag.putUUID(NETWORK_ID_TAG, networkId.value());
    }

    /** Новая загрузка чанка всегда получает новый token; расписание не переносится через NBT. */
    public void onLoad() {
        resetTransientState();
    }

    private void resetTransientState() {
        token = null;
        endpoint = null;
        activeEpoch = null;
        completedEpoch = null;
        failureLoggedEpoch = null;
        activeParameters = null;
        cachedGeometry = null;
        observedConfigRevision = Long.MIN_VALUE;
        cachedMode = null;
        cachedPlacement = null;
        registrationFailureLogged = false;
        noScheduleLogged = false;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public NetworkId getOrCreateNetworkIdForBinding(RadarControllerBlockEntity owner) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Radar NetworkId is only available on the logical server");
        }
        endpoint = endpoint(level, owner.getBlockPos());
        if (networkId == null) {
            networkId = NetworkId.create();
            loadedNetworkId = false;
            owner.setChanged();
            RadarDiagnostics.log("network_id_created", networkId, endpoint, null, tick(level),
                    "reason=item_binding_before_first_server_tick");
        }
        return networkId;
    }

    public void applyNetworkIdFromItem(RadarControllerBlockEntity owner, NetworkId replacement) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Radar NetworkId can only be applied on the logical server");
        }
        if (token != null) throw new IllegalStateException("Cannot replace a registered radar NetworkId");
        networkId = replacement;
        loadedNetworkId = false;
        endpoint = endpoint(level, owner.getBlockPos());
        completedEpoch = null;
        activeEpoch = null;
        activeParameters = null;
        cachedGeometry = null;
        owner.setChanged();
        RadarDiagnostics.log("radar_item_network_id_applied", networkId, endpoint, null, tick(level),
                "source=controller_item");
    }

    public void tick(RadarControllerBlockEntity owner, ServerLevel level,
                     RadarMode mode, int panelCount, boolean assembled) {
        ensureNetworkId(owner, level);
        register(level, owner.getBlockPos());
        if (token == null || endpoint == null || networkId == null) return;
        RadarNetworkManager manager;
        try {
            manager = RadarNetworkManager.forServer(level.getServer());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("scan_skipped", networkId, endpoint, null, tick(level),
                    "reason=network_manager_unavailable", exception);
            return;
        }

        Optional<ScanWindow> scheduled = manager.scanWindowForRadar(endpoint);
        if (scheduled.isEmpty()) {
            if (!noScheduleLogged) {
                noScheduleLogged = true;
                RadarDiagnostics.log("scan_skipped", networkId, endpoint, null, manager.currentServerTick(),
                        "reason=no_schedule_for_registration");
            }
            return;
        }
        noScheduleLogged = false;
        ScanWindow window = scheduled.orElseThrow();
        if (!window.eligibleThisWindow()) return;
        if (activeEpoch == null || activeEpoch != window.epoch()) {
            activeEpoch = window.epoch();
            completedEpoch = null;
            activeParameters = RadarServerConfig.snapshot();
            cachedGeometry = null;
            cachedMode = null;
            cachedPlacement = null;
            RadarDiagnostics.log("scan_window_snapshot", networkId, endpoint, activeEpoch,
                    manager.currentServerTick(), "configRevision=" + activeParameters.revision()
                            + " boundaryTick=" + window.boundaryTick());
        }
        long now = manager.currentServerTick();
        if (completedEpoch != null && completedEpoch == window.epoch()) return;
        if (now < Math.max(0L, window.boundaryTick() - PRE_BOUNDARY_TICKS)) return;
        if (now >= window.boundaryTick()) {
            RadarDiagnostics.log("scan_skipped", networkId, endpoint, window.epoch(), now,
                    "reason=window_boundary_passed boundaryTick=" + window.boundaryTick());
            return;
        }

        RadarAreaGeometry.GeometrySnapshot geometry = null;
        List<RadarDetection> detections = List.of();
        long startedAt = System.nanoTime();
        RadarDiagnostics.log("scan_started", networkId, endpoint, window.epoch(), now,
                "mode=" + mode + " assembled=" + assembled + " panels=" + panelCount);
        try {
            if (assembled) {
                geometry = geometry(owner, level, mode, panelCount, activeParameters);
                if (panelCount > activeParameters.maxPanels()
                        && lastLimitWarningRevision != activeParameters.revision()) {
                    lastLimitWarningRevision = activeParameters.revision();
                    RadarDiagnostics.log("assembly_limit_reduced", networkId, endpoint, window.epoch(), now,
                            "capturedPanels=" + panelCount + " activeLimit=" + activeParameters.maxPanels()
                                    + " action=range_uses_limit_panels_assembly_kept");
                }
                EntityRadarScanner.ScanResult result = SCANNER.scan(level, geometry.scanArea(), ELIGIBLE);
                detections = result.detections();
                RadarDiagnostics.log("scan_candidates", networkId, endpoint, window.epoch(),
                        manager.currentServerTick(), "candidates=" + result.candidateCount()
                                + " acceptedHitboxes=" + result.acceptedHitboxCount()
                                + " found=" + detections.size());
            }
        } catch (RuntimeException exception) {
            if (failureLoggedEpoch == null || failureLoggedEpoch != window.epoch()) {
                failureLoggedEpoch = window.epoch();
                RadarDiagnostics.logException("scan_failed", networkId, endpoint, window.epoch(),
                        manager.currentServerTick(), "retry=next_preboundary_tick", exception);
            }
            return;
        }

        long finished = manager.currentServerTick();
        if (finished >= window.boundaryTick()) {
            RadarDiagnostics.log("report_rejected", networkId, endpoint, window.epoch(), finished,
                    "reason=window_boundary_passed found=" + detections.size());
            return;
        }
        try {
            boolean accepted = manager.submitCompletedScan(endpoint, networkId, token,
                    window.epoch(), detections);
            if (accepted) completedEpoch = window.epoch();
            String refs = RadarDiagnostics.enabled()
                    ? detections.stream().map(detection -> detection.target().toString())
                            .collect(Collectors.joining(",", "[", "]"))
                    : "[]";
            RadarDiagnostics.log(accepted ? "report_submitted" : "report_rejected",
                    networkId, endpoint, window.epoch(), manager.currentServerTick(),
                    "accepted=" + accepted + " found=" + detections.size()
                            + " durationNanos=" + (System.nanoTime() - startedAt)
                            + " targets=" + refs + " emptyReason=" + (assembled ? "none" : "not_assembled"));
        } catch (RuntimeException exception) {
            if (failureLoggedEpoch == null || failureLoggedEpoch != window.epoch()) {
                failureLoggedEpoch = window.epoch();
                RadarDiagnostics.logException("report_submission_failed", networkId, endpoint,
                        window.epoch(), manager.currentServerTick(), "retry=next_preboundary_tick", exception);
            }
        }
    }

    public Optional<RadarVisualProfile> visualProfile(RadarControllerBlockEntity owner) {
        if (!(owner.getLevel() instanceof ServerLevel level) || !owner.assembly().isAssembled()) {
            return Optional.empty();
        }
        RadarMode mode = owner.mode();
        DirectionAndPosition direction = new DirectionAndPosition(
                owner.getBlockState().getValue(RadarControllerBlock.FACING), owner.getBlockPos());
        RadarServerConfig.Snapshot observed = RadarServerConfig.snapshot();
        if (observedConfigRevision != observed.revision()) {
            cachedGeometry = null;
            cachedMode = null;
            cachedPlacement = null;
            observedConfigRevision = observed.revision();
        }
        RadarServerConfig.Snapshot parameters = activeParameters == null ? observed : activeParameters;
        RadarAreaGeometry.GeometrySnapshot geometry = geometry(owner, level, mode,
                owner.assembly().panelCount(), parameters, direction);
        return Optional.of(geometry.profile());
    }

    private RadarAreaGeometry.GeometrySnapshot geometry(RadarControllerBlockEntity owner, ServerLevel level,
                                                        RadarMode mode, int panelCount,
                                                        RadarServerConfig.Snapshot parameters) {
        return geometry(owner, level, mode, panelCount, parameters,
                new DirectionAndPosition(owner.getBlockState().getValue(RadarControllerBlock.FACING),
                        owner.getBlockPos()));
    }

    private RadarAreaGeometry.GeometrySnapshot geometry(RadarControllerBlockEntity owner, ServerLevel level,
                                                        RadarMode mode, int panelCount,
                                                        RadarServerConfig.Snapshot parameters,
                                                        DirectionAndPosition placement) {
        RadarAreaGeometry.GeometrySnapshot current = cachedGeometry;
        if (current != null && current.configRevision() == parameters.revision()
                && current.countedPanels() == Math.min(panelCount, parameters.maxPanels())
                && cachedMode == mode && placement.equals(cachedPlacement)) return current;
        cachedGeometry = RadarAreaGeometry.create(level.dimension().location(), placement.position(),
                placement.facing(), mode, panelCount, parameters);
        cachedMode = mode;
        cachedPlacement = placement;
        RadarDiagnostics.log("radar_profile_published", networkId, endpoint, activeEpoch, tick(level),
                "mode=" + mode + " range=" + cachedGeometry.range() + " angle="
                        + cachedGeometry.horizontalAngle() + " minY=" + cachedGeometry.minYOffset()
                        + " maxY=" + cachedGeometry.maxYOffset() + " parts=" + parameters.visualParts());
        return cachedGeometry;
    }

    public void invalidateProfile() {
        cachedGeometry = null;
        cachedMode = null;
        cachedPlacement = null;
    }

    public void unregister(String reason, RadarControllerBlockEntity owner) {
        if (token == null || endpoint == null || networkId == null
                || !(owner.getLevel() instanceof ServerLevel level)) {
            token = null;
            endpoint = null;
            return;
        }
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(level.getServer());
            boolean removed = manager.unregister(endpoint, networkId, token);
            RadarDiagnostics.log(removed ? "radar_unregistered" : "radar_unregister_rejected",
                    networkId, endpoint, null, manager.currentServerTick(),
                    "reason=" + reason + " token=" + token.value());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("radar_unregister_failed", networkId, endpoint, null,
                    tick(level), "reason=" + reason, exception);
        } finally {
            token = null;
            endpoint = null;
        }
    }

    private void ensureNetworkId(RadarControllerBlockEntity owner, ServerLevel level) {
        endpoint = endpoint(level, owner.getBlockPos());
        if (networkId == null) {
            networkId = NetworkId.create();
            owner.setChanged();
            RadarDiagnostics.log("network_id_created", networkId, endpoint, null, tick(level),
                    "reason=missing_saved_id");
            return;
        }
        if (loadedNetworkId) {
            RadarDiagnostics.log("network_id_loaded", networkId, endpoint, null, tick(level),
                    "source=block_entity_nbt");
            loadedNetworkId = false;
        }
    }

    private void register(ServerLevel level, BlockPos pos) {
        if (token != null || networkId == null) return;
        endpoint = endpoint(level, pos);
        try {
            token = RadarNetworkManager.forServer(level.getServer()).registerRadar(endpoint, networkId, POLICY);
            registrationFailureLogged = false;
            RadarDiagnostics.log("radar_registration_ready", networkId, endpoint, null, tick(level),
                    "token=" + token.value() + " policy=" + POLICY);
        } catch (RuntimeException exception) {
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarDiagnostics.logException("radar_registration_failed", networkId, endpoint, null,
                        tick(level), "operation=registerRadar", exception);
            }
        }
    }

    private static EndpointKey.Static endpoint(ServerLevel level, BlockPos pos) {
        return new EndpointKey.Static(level.dimension(), pos);
    }

    private long tick(ServerLevel level) {
        try {
            return RadarNetworkManager.forServer(level.getServer()).currentServerTick();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    public EndpointKey.Static endpoint() {
        return endpoint;
    }

    public long currentTick(ServerLevel level) {
        return tick(level);
    }

    private record DirectionAndPosition(net.minecraft.core.Direction facing, BlockPos position) {
    }
}
