package com.electroradar.client.monitor;

import com.simibubi.create.foundation.data.CreateRegistrate;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.RadarDisplayContent;
import com.electroradar.radar.controller.RadarControllerContent;
import com.electroradar.client.controller.RadarPanelCTBehaviour;
import com.electroradar.client.controller.OverviewModuleCTBehaviour;
import com.electroradar.radar.monitor.network.MonitorOpenPayload;
import com.electroradar.radar.monitor.network.MonitorPayloads;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;

/** Только клиентские регистрации: Create CT, корневой рендерер и обслуживание временного кэша. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class MonitorClientEvents {
    private MonitorClientEvents() {
    }

    /** Регистрирует обработчики server-to-client только в физическом клиенте. */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MonitorPayloads.PROTOCOL_VERSION);
        registrar.playToClient(MonitorSnapshotPayload.TYPE, MonitorSnapshotPayload.STREAM_CODEC,
                (payload, context) -> MonitorClientPayloadHandler.handle(payload));
        registrar.playToClient(MonitorOpenPayload.TYPE, MonitorOpenPayload.STREAM_CODEC,
                (payload, context) -> MonitorClientPayloadHandler.handle(payload));
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> CreateRegistrate
                .connectedTextures(RadarDisplayCTBehaviour::new)
                .accept(RadarDisplayContent.RADAR_DISPLAY_BLOCK.get()));
        event.enqueueWork(() -> CreateRegistrate
                .connectedTextures(RadarPanelCTBehaviour::new)
                .accept(RadarControllerContent.RADAR_PANEL.get()));
        event.enqueueWork(() -> CreateRegistrate
                .connectedTextures(OverviewModuleCTBehaviour::new)
                .accept(RadarControllerContent.OVERVIEW_MODULE.get()));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RadarDisplayContent.RADAR_DISPLAY_ENTITY.get(), RadarDisplayRenderer::new);
    }

    /** Удаляет кэш после выгрузки мира или замены корневого блока; уровни ради этого не загружаются. */
    @EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
    public static final class ClientRuntimeEvents {
        private ClientRuntimeEvents() {
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            MonitorClientState.advanceClientClock();
            MonitorClientState.pruneLoadedRoots();
        }
    }
}
