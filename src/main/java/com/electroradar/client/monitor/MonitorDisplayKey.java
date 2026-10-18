package com.electroradar.client.monitor;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Клиентский ключ состояния одной текущей загрузки физического монитора. */
public record MonitorDisplayKey(ResourceLocation dimension, BlockPos rootPosition,
                                UUID displayId, UUID loadId) {
    public MonitorDisplayKey {
        rootPosition = rootPosition.immutable();
    }
}
