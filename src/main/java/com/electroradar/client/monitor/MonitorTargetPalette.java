package com.electroradar.client.monitor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import com.electroradar.config.RadarClientConfig;

/**
 * Базовая палитра меток старого монитора, применяемая без запроса сущностей из игрового мира.
 * Классификация использует только строковый идентификатор, уже пришедший в клиентском снимке,
 * и локальный реестр типов Minecraft.
 */
final class MonitorTargetPalette {
    private MonitorTargetPalette() {
    }

    /** Возвращает RGB-цвет игрока, враждебного/пассивного существа или неизвестной цели. */
    static int entityRgb(MonitorClientState.DisplayTarget target) {
        RadarClientConfig.TargetColors palette = RadarClientConfig.palette().targets();
        ResourceLocation id = ResourceLocation.tryParse(target.entityTypeId());
        if (id == null) return palette.other();
        if (id.equals(ResourceLocation.withDefaultNamespace("player"))) return palette.player();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) return palette.other();
        MobCategory category = type.getCategory();
        if (category == MobCategory.MONSTER) return palette.hostile();
        if (category != MobCategory.MISC) return palette.passive();
        return palette.other();
    }
}
