package com.electroradar.radar.network;

import java.util.List;

/**
 * Неизменяемый результат запроса целей сети.
 *
 * <p>Список создаётся заново для конкретного запроса и не хранится в сетевом состоянии.
 * {@code version} — единственный счётчик видимого состояния сети: он растёт только тогда, когда
 * меняется что-то, что потребитель может увидеть (состав целей, положение, заморозка, выбор,
 * регистрации, смена окна). Пустой список не сам по себе признак ошибки доступа.</p>
 *
 * @param <T> тип записей, выбранный {@link TargetProjection}
 * @param version версия видимого состояния сети на момент ответа; 0 для закрытой связи
 * @param targets все уникальные цели, имеющие источник среди доступных радаров
 */
public record TargetSnapshot<T>(long version, List<T> targets) {
    public TargetSnapshot {
        if (version < 0) throw new IllegalArgumentException("Snapshot version must be non-negative");
        targets = List.copyOf(targets);
    }

    /** Пустой ответ для закрытой или неактуальной связи. */
    public static <T> TargetSnapshot<T> empty() {
        return new TargetSnapshot<>(0L, List.of());
    }
}
