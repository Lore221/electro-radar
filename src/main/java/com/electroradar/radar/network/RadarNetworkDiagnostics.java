package com.electroradar.radar.network;

import java.util.Map;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/** Форматирует полный опубликованный снимок отдельно от переходов состояния сетевого ядра. */
final class RadarNetworkDiagnostics {
    private RadarNetworkDiagnostics() {
    }

    /**
     * Записывает каждую цель, каждый источник и live-данные одной завершённой публикации.
     * При выключенном INFO-профиле сериализация и сортировка целей не выполняются.
     */
    static void logPublishedSnapshot(NetworkRuntime network, long serverTick) {
        if (!RadarDiagnostics.enabled()) {
            return;
        }
        StringBuilder data = new StringBuilder("[");
        boolean firstTarget = true;
        for (TargetRef target : network.targets.keySet().stream().sorted(RadarNetworkState.TARGET_ORDER).toList()) {
            if (!firstTarget) {
                data.append(',');
            }
            firstTarget = false;
            TargetEntry entry = network.targets.get(target);
            data.append("{target=").append(target).append(",sources=[");
            boolean firstSource = true;
            for (Map.Entry<EndpointKey, SourceLink> source : entry.sources.entrySet()) {
                if (!firstSource) {
                    data.append(',');
                }
                firstSource = false;
                SourceLink link = source.getValue();
                data.append("{radar=").append(source.getKey())
                        .append(",age=").append(network.publishedEpoch - link.lastSeenEpoch)
                        .append(",lastDetectedTick=").append(link.lastDetectedTick)
                        .append(",classification=").append(link.classification).append('}');
            }
            data.append("],live=").append(formatLiveResult(entry.liveResult)).append('}');
        }
        data.append(']');
        RadarDiagnostics.log("published_snapshot", network.id, null, network.publishedEpoch, serverTick,
                "revision=" + network.revision + " targets=" + network.targets.size() + " data=" + data);
    }

    /** Включает координаты и type для живой сущности, либо тик/epoch недоступного lookup. */
    private static String formatLiveResult(LiveResult liveResult) {
        if (liveResult == null) {
            return "UNAVAILABLE(reason=not_sampled)";
        }
        if (liveResult.availability() == TargetAvailability.UNAVAILABLE) {
            return "UNAVAILABLE sampledEpoch=" + liveResult.sampledEpoch()
                    + " sampledTick=" + liveResult.sampledTick();
        }
        var position = liveResult.position().orElseThrow();
        var entityType = liveResult.entityType().orElseThrow();
        return "AVAILABLE(x=" + position.x() + " y=" + position.y() + " z=" + position.z()
                + " type=" + entityType + " sampledEpoch=" + liveResult.sampledEpoch()
                + " sampledTick=" + liveResult.sampledTick() + ')';
    }
}
