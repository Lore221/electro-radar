package com.electroradar.radar.network;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Устойчивая идентичность цели, различающая измерение, вид источника и UUID. */
public record TargetRef(ResourceKey<Level> dimension, SourceKind sourceKind, UUID id) {
    public TargetRef {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(id, "id");
    }

    public enum SourceKind {
        /** Обычная игровая сущность, идентифицируемая её UUID. */
        ENTITY,
        /** Сущность движущейся конструкции, для которой нужен отдельный адаптер цели. */
        MOVING_STRUCTURE
    }
}
