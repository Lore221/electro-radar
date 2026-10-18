package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Живые данные цели, полученные один раз для опубликованной версии сети. */
public record LiveResult(
        long sampledEpoch,
        long sampledTick,
        TargetAvailability availability,
        Optional<Vec3> position,
        Optional<EntityType<?>> entityType
) {
    public LiveResult {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(entityType, "entityType");
        if (sampledEpoch < 0 || sampledTick < 0) {
            throw new IllegalArgumentException("Live result time values must be non-negative");
        }
        if (availability == TargetAvailability.AVAILABLE && (position.isEmpty() || entityType.isEmpty())) {
            throw new IllegalArgumentException("Available targets require a position and entity type");
        }
        if (availability == TargetAvailability.UNAVAILABLE && (position.isPresent() || entityType.isPresent())) {
            throw new IllegalArgumentException("Unavailable targets cannot expose live values");
        }
    }

    /** Создаёт результат для цели, найденной и скопированной из загруженного серверного мира. */
    public static LiveResult available(long epoch, long tick, Vec3 position, EntityType<?> entityType) {
        return new LiveResult(epoch, tick, TargetAvailability.AVAILABLE,
                Optional.of(Objects.requireNonNull(position, "position")),
                Optional.of(Objects.requireNonNull(entityType, "entityType")));
    }

    /** Создаёт явный результат недоступности без выдуманных координат или типа. */
    public static LiveResult unavailable(long epoch, long tick) {
        return new LiveResult(epoch, tick, TargetAvailability.UNAVAILABLE, Optional.empty(), Optional.empty());
    }
}
