package com.electroradar;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import com.electroradar.config.RadarClientConfig;
import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.controller.RadarControllerContent;
import com.electroradar.radar.controller.RadarControllerBlockEntity;
import com.electroradar.radar.controller.RadarPanelTiltPayloads;
import com.electroradar.radar.controller.RadarPanelContraption;
import com.electroradar.radar.monitor.RadarDisplayContent;
import com.electroradar.radar.linker.LinkerContent;
import com.electroradar.radar.monitor.network.MonitorPayloads;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.NetworkOutlinePayloads;
import com.electroradar.radar.network.NetworkOutlineServer;

@Mod(ElectroRadar.MOD_ID)
public final class ElectroRadar {
    public static final String MOD_ID = "electroradar";

    /** Подключает создание, тиканье и очистку серверного менеджера к lifecycle-событиям NeoForge. */
    public ElectroRadar(IEventBus modEventBus, ModContainer modContainer) {
        RadarControllerContent.register(modEventBus);
        RadarControllerContent.registerCreateMovementPolicy();
        RadarDisplayContent.register(modEventBus);
        LinkerContent.register(modEventBus);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, RadarClientConfig.SPEC,
                "electroradar-client.toml");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, RadarServerConfig.SPEC,
                "electroradar-server.toml");
        modEventBus.addListener(RadarClientConfig::onConfigChanged);
        modEventBus.addListener(RadarServerConfig::onConfigChanged);
        modEventBus.addListener(MonitorPayloads::register);
        modEventBus.addListener(NetworkOutlinePayloads::register);
        modEventBus.addListener(RadarPanelTiltPayloads::register);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStarting);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerStarting);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(MonitorPayloads::onChunkWatched);
        NeoForge.EVENT_BUS.addListener(RadarControllerBlockEntity::onEntityStartTracking);
        NeoForge.EVENT_BUS.addListener(RadarPanelContraption::onEntityTick);
    }
}
