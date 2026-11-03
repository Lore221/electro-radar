package com.electroradar.client.cannon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import com.electroradar.ElectroRadar;
import com.electroradar.cannon.controller.TrajectoryMode;

/** Подключает клиентские иконки режима траектории; серверу они не нужны. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class TargetControllerClientEvents {
    private TargetControllerClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        TrajectoryMode.useIcons(TrajectoryModeIcon::of);
    }
}
