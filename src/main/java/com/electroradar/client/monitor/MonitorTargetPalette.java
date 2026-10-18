package com.electroradar.client.monitor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

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
        ResourceLocation id = ResourceLocation.tryParse(target.entityTypeId());
        if (id == null) return 0xFFFF00;
        if (id.equals(ResourceLocation.withDefaultNamespace("player"))) return 0x0000FF;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) return 0xFFFF00;
        MobCategory category = type.getCategory();
        if (category == MobCategory.MONSTER) return 0xFF0000;
        if (category != MobCategory.MISC) return 0x55FF55;
        return 0xFFFF00;
    }
}
