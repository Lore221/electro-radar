package com.electroradar.radar.controller;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Общая регистрация исходящего угла; настоящий обработчик загружается только на клиенте. */
public final class RadarPanelTiltPayloads {
    private RadarPanelTiltPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER) {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToClient(RadarPanelTiltPayload.TYPE, RadarPanelTiltPayload.STREAM_CODEC,
                    (payload, context) -> { });
        }
    }
}
