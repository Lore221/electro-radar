package com.electroradar.radar.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Читает подготовленный менеджером профиль и ту же серверную фазу, по которой шёл поиск. */
final class MonitorVisualProfileCollector {
    private boolean failureLogged;

    void reset() { failureLogged = false; }

    VisualWindow collect(ServerLevel serverLevel, NetworkId networkId, BlockPos monitorPosition) {
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(serverLevel.getServer());
            List<RadarNetworkManager.RadarVisualWindow> windows = manager.registeredEndpoints(networkId).stream()
                    .filter(EndpointKey.Static.class::isInstance)
                    .map(EndpointKey.Static.class::cast)
                    .filter(endpoint -> endpoint.dimension().equals(serverLevel.dimension()))
                    .map(manager::visualWindowForRadar).flatMap(java.util.Optional::stream)
                    .sorted(Comparator.comparing(window -> window.profile().radarPosition())).toList();
            List<RadarVisualProfile> profiles = new ArrayList<>(windows.size());
            List<MonitorVisualPhase> phases = new ArrayList<>(windows.size());
            for (RadarNetworkManager.RadarVisualWindow window : windows) {
                profiles.add(window.profile());
                phases.add(new MonitorVisualPhase(window.profile().radarPosition(), window.geometryRevision(),
                        window.slotSerial(), window.activeSectorIndex(), window.sectorOrderVariant(),
                        window.startTick(), window.endTick()));
            }
            long now = manager.currentServerTick();
            failureLogged = false;
            return new VisualWindow(profiles, phases, now);
        } catch (RuntimeException exception) {
            if (!failureLogged) {
                failureLogged = true;
                RadarDiagnostics.logException("monitor_visual_profile_collection_failed", networkId,
                        new EndpointKey.Static(serverLevel.dimension(), monitorPosition), null,
                        serverLevel.getGameTime(), "operation=read_global_visual_phase", exception);
            }
            return VisualWindow.EMPTY;
        }
    }

    record VisualWindow(List<RadarVisualProfile> profiles, List<MonitorVisualPhase> phases,
                        long observedServerTick) {
        static final VisualWindow EMPTY = new VisualWindow(List.of(), List.of(), -1L);
        VisualWindow {
            profiles = List.copyOf(profiles);
            phases = List.copyOf(phases);
        }
    }
}
