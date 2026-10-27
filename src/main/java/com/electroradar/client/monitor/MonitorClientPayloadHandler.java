package com.electroradar.client.monitor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import com.electroradar.radar.monitor.network.MonitorOpenPayload;
import com.electroradar.radar.monitor.network.MonitorDeltaPayload;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;

/** Клиентские обработчики, запускаемые на главном потоке игры. */
public final class MonitorClientPayloadHandler {
    private MonitorClientPayloadHandler() {
    }

    /** Применяет часть к уже известному физическому экрану; этот пакет никогда не открывает GUI. */
    public static void handle(MonitorSnapshotPayload payload) {
        MonitorClientState.accept(payload);
    }

    /** Применяет проверяемый абсолютный delta только к совместимому baseline. */
    public static void handle(MonitorDeltaPayload payload) {
        MonitorClientState.accept(payload);
    }

    /** Выполняет только отдельную команду открытия, полученную после серверного взаимодействия. */
    public static void handle(MonitorOpenPayload payload) {
        MonitorClientState.handle(payload);
    }
}
