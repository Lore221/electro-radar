package com.electroradar.radar.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.ScanWindow;
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.visual.RadarVisualProfileSource;

/** Серверное чтение визуальных профилей без загрузки чанков и изменения регистраций. */
final class MonitorVisualProfileCollector {
    private final Set<EndpointKey> visualProfileFailuresLogged = new HashSet<>();
    private boolean visualProfileCollectionFailureLogged;

    void reset() {
        visualProfileFailuresLogged.clear();
        visualProfileCollectionFailureLogged = false;
    }

    /**
     * Собирает только профили зарегистрированных статических радаров текущей сети.
     * Чтение не загружает чанки и не трогает регистрацию; незагруженные BlockEntity и endpoint
     * другого измерения пропускаются. Окно берётся у действующего расписания, а не вычисляется
     * делением gameTime, чтобы визуальный круг совпадал с публикацией ядра.
     */
    VisualWindow collect(ServerLevel serverLevel, NetworkId networkId, BlockPos monitorPosition) {
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(serverLevel.getServer());
            VisualWindow result = collectRegistered(serverLevel, networkId, manager);
            visualProfileCollectionFailureLogged = false;
            return result;
        } catch (RuntimeException exception) {
            if (!visualProfileCollectionFailureLogged) {
                visualProfileCollectionFailureLogged = true;
                RadarDiagnostics.logException("monitor_visual_profile_collection_failed", networkId,
                        new EndpointKey.Static(serverLevel.dimension(), monitorPosition), null,
                        serverLevel.getGameTime(), "operation=read_registered_visual_profiles", exception);
            }
            return VisualWindow.EMPTY;
        }
    }

    private VisualWindow collectRegistered(ServerLevel level, NetworkId networkId, RadarNetworkManager manager) {
        long currentTick = manager.currentServerTick();
        List<EndpointKey.Static> endpoints = manager.registeredEndpoints(networkId).stream()
                .filter(EndpointKey.Static.class::isInstance)
                .map(EndpointKey.Static.class::cast)
                .filter(endpoint -> endpoint.dimension().equals(level.dimension()))
                .sorted(Comparator.comparing(EndpointKey.Static::position))
                .toList();
        visualProfileFailuresLogged.retainAll(new HashSet<>(endpoints));

        List<RadarVisualProfile> profiles = new ArrayList<>();
        ScanWindow activeWindow = null;
        ScanWindow fallbackWindow = null;
        for (EndpointKey.Static endpoint : endpoints) {
            Optional<ScheduledProfile> provided = readProfile(level, networkId, manager, endpoint, currentTick);
            if (provided.isEmpty()) continue;
            ScheduledProfile profile = provided.orElseThrow();
            profiles.add(profile.profile());
            if (profile.window().eligibleThisWindow()) {
                activeWindow = earlier(activeWindow, profile.window());
            } else {
                fallbackWindow = earlier(fallbackWindow, profile.window());
            }
        }
        profiles.sort(Comparator.comparing(RadarVisualProfile::radarPosition));
        ScanWindow window = activeWindow == null ? fallbackWindow : activeWindow;
        long period = window == null ? 0L : window.periodTicks();
        long remaining = window == null ? 0L
                : Math.max(0L, Math.min(period, window.boundaryTick() - currentTick));
        return new VisualWindow(profiles, period, remaining,
                window == null ? -1L : window.boundaryTick(), currentTick);
    }

    private Optional<ScheduledProfile> readProfile(ServerLevel level, NetworkId networkId,
                                                  RadarNetworkManager manager, EndpointKey.Static endpoint,
                                                  long currentTick) {
        if (!level.hasChunkAt(endpoint.position())) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(endpoint.position());
        if (!(blockEntity instanceof RadarVisualProfileSource source)) return Optional.empty();
        try {
            if (source.visualNetworkId().filter(networkId::equals).isEmpty()) return Optional.empty();
            Optional<RadarVisualProfile> provided = source.radarVisualProfile();
            if (provided.isEmpty()) return Optional.empty();
            RadarVisualProfile profile = provided.orElseThrow();
            if (!profile.dimension().equals(endpoint.dimension().location())
                    || !profile.radarPosition().equals(endpoint.position())) {
                throw new IllegalStateException("Radar visual profile identity does not match its endpoint");
            }
            Optional<ScanWindow> scheduled = manager.scanWindowForRadar(endpoint);
            if (scheduled.isEmpty()) return Optional.empty();
            visualProfileFailuresLogged.remove(endpoint);
            return Optional.of(new ScheduledProfile(profile, scheduled.orElseThrow()));
        } catch (RuntimeException exception) {
            if (visualProfileFailuresLogged.add(endpoint)) {
                RadarDiagnostics.logException("monitor_visual_profile_read_failed", networkId,
                        endpoint, null, currentTick, "operation=read_profile_or_scan_schedule", exception);
            }
            return Optional.empty();
        }
    }

    private static ScanWindow earlier(ScanWindow current, ScanWindow candidate) {
        return current == null || candidate.boundaryTick() < current.boundaryTick() ? candidate : current;
    }

    private record ScheduledProfile(RadarVisualProfile profile, ScanWindow window) {
    }

    record VisualWindow(List<RadarVisualProfile> profiles, long periodTicks, long remainingTicks,
                        long nextBoundaryTick, long observedServerTick) {
        static final VisualWindow EMPTY = new VisualWindow(List.of(), 0L, 0L, -1L, -1L);

        VisualWindow {
            profiles = List.copyOf(profiles);
        }
    }
}
