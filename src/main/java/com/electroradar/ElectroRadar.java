package com.electroradar;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import com.electroradar.radar.debug.DebugRadarContent;
import com.electroradar.radar.monitor.RadarDisplayContent;
import com.electroradar.radar.monitor.network.MonitorPayloads;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.NetworkOutlinePayloads;
import com.electroradar.radar.network.NetworkOutlineServer;

@Mod(ElectroRadar.MOD_ID)
public final class ElectroRadar {
    public static final String MOD_ID = "electroradar";

    /** Подключает создание, тиканье и очистку серверного менеджера к lifecycle-событиям NeoForge. */
    public ElectroRadar(IEventBus modEventBus) {
        DebugRadarContent.register(modEventBus);
        RadarDisplayContent.register(modEventBus);
        modEventBus.addListener(MonitorPayloads::register);
        modEventBus.addListener(NetworkOutlinePayloads::register);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStarting);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerStarting);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NetworkOutlineServer::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(MonitorPayloads::onChunkWatched);
    }
}
