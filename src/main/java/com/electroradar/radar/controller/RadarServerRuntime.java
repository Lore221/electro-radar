package com.electroradar.radar.controller;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
final class RadarServerRuntime {
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
    private CachedGeometry cachedGeometry;
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
        invalidateProfile();
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
        invalidateProfile();
        owner.setChanged();
        RadarDiagnostics.log("radar_item_network_id_applied", networkId, endpoint, null, tick(level),
                "source=controller_item");
    }

    public void tick(RadarControllerBlockEntity owner, ServerLevel level,
                     RadarMode mode, int panelCount, boolean assembled) {
        ensureNetworkId(owner, level);
        register(level, owner.getBlockPos());
        if (token == null || endpoint == null || networkId == null) return;
        RadarNetworkManager manager = networkManager(level);
        if (manager == null) return;
        ScanWindow window = prepareScanWindow(manager);
        if (window == null) return;

        long startedAt = System.nanoTime();
        List<RadarDetection> detections;
        try {
            detections = scan(owner, level, manager, window, mode, panelCount, assembled);
        } catch (RuntimeException exception) {
            logWindowFailure("scan_failed", manager, window, exception);
            return;
        }
        submitReport(manager, window, detections, assembled, startedAt);
    }

    private RadarNetworkManager networkManager(ServerLevel level) {
        try {
            return RadarNetworkManager.forServer(level.getServer());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("scan_skipped", networkId, endpoint, null, tick(level),
                    "reason=network_manager_unavailable", exception);
            return null;
        }
    }

    /** Закрепляет параметры один раз на эпоху; проход разрешён только перед её границей. */
    private ScanWindow prepareScanWindow(RadarNetworkManager manager) {
        Optional<ScanWindow> scheduled = manager.scanWindowForRadar(endpoint);
        if (scheduled.isEmpty()) {
            if (!noScheduleLogged) {
                noScheduleLogged = true;
                RadarDiagnostics.log("scan_skipped", networkId, endpoint, null, manager.currentServerTick(),
                        "reason=no_schedule_for_registration");
            }
            return null;
        }
        noScheduleLogged = false;
        ScanWindow window = scheduled.orElseThrow();
        if (!window.eligibleThisWindow()) return null;
        if (activeEpoch == null || activeEpoch != window.epoch()) {
            activeEpoch = window.epoch();
            completedEpoch = null;
            activeParameters = RadarServerConfig.snapshot();
            RadarDiagnostics.log("scan_window_snapshot", networkId, endpoint, activeEpoch,
                    manager.currentServerTick(), "configRevision=" + activeParameters.revision()
                            + " boundaryTick=" + window.boundaryTick());
        }
        long now = manager.currentServerTick();
        if (completedEpoch != null && completedEpoch == window.epoch()) return null;
        if (now < Math.max(0L, window.boundaryTick() - PRE_BOUNDARY_TICKS)) return null;
        if (now >= window.boundaryTick()) {
            RadarDiagnostics.log("scan_skipped", networkId, endpoint, window.epoch(), now,
                    "reason=window_boundary_passed boundaryTick=" + window.boundaryTick());
            return null;
        }
        return window;
    }

    private List<RadarDetection> scan(RadarControllerBlockEntity owner, ServerLevel level,
                                      RadarNetworkManager manager, ScanWindow window,
                                      RadarMode mode, int panelCount, boolean assembled) {
        RadarDiagnostics.log("scan_started", networkId, endpoint, window.epoch(), manager.currentServerTick(),
                "mode=" + mode + " assembled=" + assembled + " panels=" + panelCount);
        if (!assembled) return List.of();
        RadarAreaGeometry.GeometrySnapshot geometry = geometry(owner, level, mode, panelCount, activeParameters);
        warnPanelLimit(manager, window, panelCount);
        EntityRadarScanner.ScanResult result = SCANNER.scan(level, geometry.scanArea(), ELIGIBLE);
        RadarDiagnostics.log("scan_candidates", networkId, endpoint, window.epoch(),
                manager.currentServerTick(), "candidates=" + result.candidateCount()
                        + " acceptedHitboxes=" + result.acceptedHitboxCount()
                        + " found=" + result.detections().size());
        return result.detections();
    }

    private void warnPanelLimit(RadarNetworkManager manager, ScanWindow window, int panelCount) {
        if (panelCount <= activeParameters.maxPanels()
                || lastLimitWarningRevision == activeParameters.revision()) return;
        lastLimitWarningRevision = activeParameters.revision();
        RadarDiagnostics.log("assembly_limit_reduced", networkId, endpoint, window.epoch(),
                manager.currentServerTick(), "capturedPanels=" + panelCount
                        + " activeLimit=" + activeParameters.maxPanels()
                        + " action=range_uses_limit_panels_assembly_kept");
    }

    /** Отмечает эпоху завершённой только после приёма полного отчёта менеджером. */
    private void submitReport(RadarNetworkManager manager, ScanWindow window,
                              List<RadarDetection> detections, boolean assembled, long startedAt) {
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
            logReport(manager, window, detections, assembled, startedAt, accepted);
        } catch (RuntimeException exception) {
            logWindowFailure("report_submission_failed", manager, window, exception);
        }
    }

    private void logReport(RadarNetworkManager manager, ScanWindow window, List<RadarDetection> detections,
                           boolean assembled, long startedAt, boolean accepted) {
        String refs = RadarDiagnostics.enabled()
                ? detections.stream().map(detection -> detection.target().toString())
                        .collect(Collectors.joining(",", "[", "]"))
                : "[]";
        RadarDiagnostics.log(accepted ? "report_submitted" : "report_rejected",
                networkId, endpoint, window.epoch(), manager.currentServerTick(),
                "accepted=" + accepted + " found=" + detections.size()
                        + " durationNanos=" + (System.nanoTime() - startedAt)
                        + " targets=" + refs + " emptyReason=" + (assembled ? "none" : "not_assembled"));
    }

    private void logWindowFailure(String event, RadarNetworkManager manager, ScanWindow window,
                                  RuntimeException exception) {
        if (failureLoggedEpoch != null && failureLoggedEpoch == window.epoch()) return;
        failureLoggedEpoch = window.epoch();
        RadarDiagnostics.logException(event, networkId, endpoint, window.epoch(),
                manager.currentServerTick(), "retry=next_preboundary_tick", exception);
    }

    public Optional<RadarVisualProfile> visualProfile(RadarControllerBlockEntity owner) {
        if (!(owner.getLevel() instanceof ServerLevel level) || !owner.assembly().isAssembled()) {
            return Optional.empty();
        }
        RadarServerConfig.Snapshot parameters = activeParameters == null
                ? RadarServerConfig.snapshot() : activeParameters;
        return Optional.of(geometry(owner, level, owner.mode(), owner.assembly().panelCount(), parameters).profile());
    }

    private RadarAreaGeometry.GeometrySnapshot geometry(RadarControllerBlockEntity owner, ServerLevel level,
                                                        RadarMode mode, int panelCount,
                                                        RadarServerConfig.Snapshot parameters) {
        GeometryKey key = new GeometryKey(parameters.revision(), Math.min(Math.max(0, panelCount), parameters.maxPanels()),
                mode, owner.getBlockState().getValue(RadarControllerBlock.FACING), owner.getBlockPos());
        if (cachedGeometry != null && cachedGeometry.key().equals(key)) return cachedGeometry.geometry();
        RadarAreaGeometry.GeometrySnapshot geometry = RadarAreaGeometry.create(level.dimension().location(),
                key.position(), key.facing(), mode, panelCount, parameters);
        cachedGeometry = new CachedGeometry(key, geometry);
        RadarDiagnostics.log("radar_profile_published", networkId, endpoint, activeEpoch, tick(level),
                "mode=" + mode + " range=" + geometry.range() + " angle="
                        + geometry.horizontalAngle() + " minY=" + geometry.minYOffset()
                        + " maxY=" + geometry.maxYOffset() + " parts=" + parameters.visualParts());
        return geometry;
    }

    public void invalidateProfile() {
        cachedGeometry = null;
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

    private record GeometryKey(long configRevision, int countedPanels, RadarMode mode,
                               Direction facing, BlockPos position) {
    }

    private record CachedGeometry(GeometryKey key, RadarAreaGeometry.GeometrySnapshot geometry) {
    }
}
