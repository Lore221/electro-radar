package com.electroradar.client.monitor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import com.electroradar.radar.monitor.network.MonitorOpenPayload;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;

/** Клиентские обработчики, запускаемые на главном потоке игры. */
public final class MonitorClientPayloadHandler {
    private MonitorClientPayloadHandler() {
    }

    /** Применяет часть к уже известному физическому экрану; этот пакет никогда не открывает GUI. */
    public static void handle(MonitorSnapshotPayload payload) {
        MonitorClientState.accept(payload);
    }

    /** Выполняет только отдельную команду открытия, полученную после серверного взаимодействия. */
    public static void handle(MonitorOpenPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())
                || !MonitorClientState.matchesLoadedRoot(level, payload.rootPosition(),
                payload.displayId(), payload.loadId())) {
            return;
        }
        MonitorDisplayKey key = new MonitorDisplayKey(payload.dimension(), payload.rootPosition(),
                payload.displayId(), payload.loadId());
        Minecraft.getInstance().setScreen(new RadarMonitorScreen(key));
    }
}
