package com.electroradar.radar.network;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Устойчивая идентичность цели, различающая измерение, вид источника и UUID. */
public record TargetRef(ResourceKey<Level> dimension, SourceKind sourceKind, UUID id) {
    /**
     * Детерминированный порядок целей для ответов ядра, пакетов и клиентского кэша.
     * Сравнивает поля напрямую, без toString: сортировки идут каждый серверный тик на каждом
     * мониторе, и строковое сравнение создавало до четырёх временных строк на одно сравнение.
     * Конкретный порядок нигде не несёт смысла, важна только его стабильность.
     */
    public static final Comparator<TargetRef> ORDER = Comparator
            .comparing((TargetRef target) -> target.dimension().location())
            .thenComparing(TargetRef::sourceKind)
            .thenComparing(TargetRef::id);

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
