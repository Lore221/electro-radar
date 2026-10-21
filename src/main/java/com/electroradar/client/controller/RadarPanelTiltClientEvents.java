package com.electroradar.client.controller;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.controller.RadarPanelTiltPayload;

/** Регистрация и тики интерполяции существуют только в физическом клиенте. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class RadarPanelTiltClientEvents {
    private RadarPanelTiltClientEvents() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(RadarPanelTiltPayload.TYPE, RadarPanelTiltPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> RadarPanelTiltClientState.accept(payload)));
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        RadarPanelTiltClientState.tick();
    }
}
