package com.electroradar;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import com.electroradar.radar.debug.DebugRadarContent;
import com.electroradar.radar.network.RadarNetworkManager;

@Mod(ElectroRadar.MOD_ID)
public final class ElectroRadar {
    public static final String MOD_ID = "electroradar";

    /** Подключает создание, тиканье и очистку серверного менеджера к lifecycle-событиям NeoForge. */
    public ElectroRadar(IEventBus modEventBus) {
        DebugRadarContent.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStarting);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RadarNetworkManager::onServerStopped);
    }
}
