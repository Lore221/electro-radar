package com.electroradar.radar.network;

import com.electroradar.radar.spatial.StructureBody;
import com.electroradar.radar.spatial.StructureMeasurement;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Последнее допустимое измерение одного подтверждающего источника, с состоянием его памяти.
 *
 * <p>Время задаётся только тиками сервера: {@code sampledTick} — когда сняты координаты,
 * {@code freezeTick} — когда наблюдение прекратилось ({@code -1}, пока цель наблюдается),
 * {@code fadeDurationTicks} — длительность затухания, зафиксированная в момент заморозки.</p>
 */
public record LiveResult(long sampledTick, TargetAvailability availability,
                         Optional<Vec3> position, Optional<EntityType<?>> entityType,
                         boolean observing, long freezeTick, long fadeDurationTicks,
                         Optional<StructureMeasurement> structure) {
    public LiveResult {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(entityType, "entityType");
        if (sampledTick < 0 || freezeTick < -1 || fadeDurationTicks < 0
                || availability == TargetAvailability.AVAILABLE && (position.isEmpty() || entityType.isPresent() == structure.isPresent())
                || availability == TargetAvailability.UNAVAILABLE && (position.isPresent() || entityType.isPresent() || structure.isPresent())
                || observing && (availability != TargetAvailability.AVAILABLE || freezeTick != -1)
                || !observing && freezeTick < 0 && availability == TargetAvailability.AVAILABLE) {
            throw new IllegalArgumentException("Invalid measured target state");
        }
    }

    public LiveResult(long sampledTick, TargetAvailability availability, Optional<Vec3> position,
            Optional<EntityType<?>> entityType, boolean observing, long freezeTick, long fadeDurationTicks) {
        this(sampledTick, availability, position, entityType, observing, freezeTick, fadeDurationTicks, Optional.empty());
    }

    public static LiveResult observedStructure(long tick, StructureBody body) {
        return new LiveResult(tick, TargetAvailability.AVAILABLE, Optional.of(body.marker()), Optional.empty(),
                true, -1, 0, Optional.of(body.measurement()));
    }

    /** Создаёт успешное измерение текущего тика; только его подтверждающий радар имеет право обновить sample. */
    public static LiveResult observed(long tick, Vec3 position, EntityType<?> entityType) {
        return new LiveResult(tick, TargetAvailability.AVAILABLE,
                Optional.of(Objects.requireNonNull(position, "position")),
                Optional.of(Objects.requireNonNull(entityType, "entityType")), true, -1, 0);
    }

    /** Замораживает sample без чтения сущности вне области и сохраняет исходный тик координат. */
    public LiveResult frozen(long tick, long fadeTicks) {
        if (!observing && availability == TargetAvailability.AVAILABLE) return this;
        return new LiveResult(sampledTick, availability, position, entityType, false, tick, fadeTicks, structure);
    }

    /** Объект был обнаружен, но не удалось получить корректное измерение. Координаты не выдумываются. */
    public static LiveResult unavailable(long tick, long freezeTick, long fadeTicks) {
        return new LiveResult(tick, TargetAvailability.UNAVAILABLE, Optional.empty(), Optional.empty(),
                false, freezeTick, fadeTicks);
    }

    /**
     * Показывает, отличается ли видимое содержимое измерения: положение, тип или переход между
     * наблюдением и памятью. sampledTick сюда намеренно не входит: повтор того же положения не
     * меняет {@code contentVersion} сети (см. границы гарантии в {@link TargetSnapshot}).
     */
    boolean sameVisibleState(LiveResult other) {
        return other != null && observing == other.observing && freezeTick == other.freezeTick
                && availability == other.availability && position.equals(other.position)
                && entityType.equals(other.entityType) && structure.equals(other.structure);
    }
}
