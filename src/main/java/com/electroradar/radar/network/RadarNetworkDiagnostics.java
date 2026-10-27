package com.electroradar.radar.network;

import java.util.Map;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.SectorConfirmation;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/** Форматирование диагностического снимка известных подтверждений без Entity и live handles. */
final class RadarNetworkDiagnostics {
    private RadarNetworkDiagnostics() { }

    static void logPublishedSnapshot(NetworkRuntime network, long serverTick) {
        if (!RadarDiagnostics.enabled()) return;
        StringBuilder data = new StringBuilder("[");
        boolean firstTarget = true;
        for (TargetRef target : network.targets.keySet().stream().sorted(RadarNetworkState.TARGET_ORDER).toList()) {
            if (!firstTarget) data.append(',');
            firstTarget = false;
            TargetEntry entry = network.targets.get(target);
            data.append("{target=").append(target).append(",sources=[");
            boolean firstSource = true;
            for (Map.Entry<EndpointKey, SourceLink> source : entry.sources.entrySet()) {
                if (!firstSource) data.append(',');
                firstSource = false;
                data.append("{radar=").append(source.getKey()).append(",sectors=[");
                boolean firstSector = true;
                for (Map.Entry<Integer, SectorConfirmation> sector : source.getValue().sectors.entrySet()) {
                    if (!firstSector) data.append(',');
                    firstSector = false;
                    SectorConfirmation confirmation = sector.getValue();
                    data.append(sector.getKey()).append(":missed=").append(confirmation.missedVisits)
                            .append(":sample=").append(confirmation.measurement).append('}');
                }
                data.append("]}");
            }
            data.append("]}");
        }
        data.append(']');
        RadarDiagnostics.log("published_snapshot", network.id, null, network.publishedEpoch, serverTick,
                "revision=" + network.revision + " measurementRevision=" + network.measurementRevision
                        + " targets=" + network.targets.size() + " data=" + data);
    }
}
