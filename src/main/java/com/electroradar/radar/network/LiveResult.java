package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Последнее допустимое измерение одного подтверждающего источника, с состоянием его памяти. */
public record LiveResult(long sampledEpoch, long sampledTick, TargetAvailability availability,
                         Optional<Vec3> position, Optional<EntityType<?>> entityType,
                         boolean observing, long freezeTick, long fadeDurationTicks) {
    public LiveResult {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(entityType, "entityType");
        if (sampledEpoch < 0 || sampledTick < 0 || freezeTick < -1 || fadeDurationTicks < 0
                || availability == TargetAvailability.AVAILABLE && (position.isEmpty() || entityType.isEmpty())
                || availability == TargetAvailability.UNAVAILABLE && (position.isPresent() || entityType.isPresent())
                || observing && (availability != TargetAvailability.AVAILABLE || freezeTick != -1)
                || !observing && freezeTick < 0 && availability == TargetAvailability.AVAILABLE) {
            throw new IllegalArgumentException("Invalid measured target state");
        }
    }

    /** Создаёт успешное измерение текущего тика; только его подтверждающий радар имеет право обновить sample. */
    public static LiveResult observed(long epoch, long tick, Vec3 position, EntityType<?> entityType) {
        return new LiveResult(epoch, tick, TargetAvailability.AVAILABLE,
                Optional.of(Objects.requireNonNull(position, "position")),
                Optional.of(Objects.requireNonNull(entityType, "entityType")), true, -1, 0);
    }

    /** Замораживает sample без чтения сущности вне области и сохраняет исходный тик координат. */
    public LiveResult frozen(long tick, long fadeTicks) {
        if (availability != TargetAvailability.AVAILABLE) {
            return new LiveResult(sampledEpoch, sampledTick, availability, position, entityType,
                    false, tick, fadeTicks);
        }
        if (!observing) return this;
        return new LiveResult(sampledEpoch, sampledTick, availability, position, entityType,
                false, tick, fadeTicks);
    }

    /** Объект был обнаружен, но не удалось получить корректное измерение. Координаты не выдумываются. */
    public static LiveResult unavailable(long epoch, long tick, long freezeTick, long fadeTicks) {
        return new LiveResult(epoch, tick, TargetAvailability.UNAVAILABLE, Optional.empty(), Optional.empty(),
                false, freezeTick, fadeTicks);
    }

    /** Совместимые фабрики остаются для остальных источников до добавления ими своего окна наблюдения. */
    public static LiveResult available(long epoch, long tick, Vec3 position, EntityType<?> entityType) {
        return observed(epoch, tick, position, entityType);
    }

    public static LiveResult unavailable(long epoch, long tick) {
        return unavailable(epoch, tick, tick, 0);
    }
}
