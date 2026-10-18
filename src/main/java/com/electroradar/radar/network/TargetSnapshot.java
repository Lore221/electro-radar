package com.electroradar.radar.network;

import java.util.List;

/**
 * Неизменяемый результат полного запроса целей одной опубликованной версии сети.
 *
 * <p>Список создаётся заново для конкретного запроса и не хранится в сетевом состоянии.
 * Пара {@code publishedEpoch}/{@code revision} позволяет потребителю сопоставить ответ с
 * ранее полученными данными. Пустой список не сам по себе признак ошибки доступа: причины
 * отказа регистрации и нормальное отсутствие доступных целей различаются в диагностике.</p>
 *
 * @param <T> тип записей, выбранный {@link TargetProjection}
 * @param publishedEpoch последняя опубликованная эпоха сети
 * @param revision ревизия видимого состояния сети
 * @param targets все уникальные цели, имеющие источник среди доступных радаров
 */
public record TargetSnapshot<T>(long publishedEpoch, long revision, List<T> targets) {
    public TargetSnapshot {
        if (publishedEpoch < 0 || revision < 0) {
            throw new IllegalArgumentException("Snapshot version values must be non-negative");
        }
        targets = List.copyOf(targets);
    }

    /** Создаёт пустой неизменяемый ответ, например для закрытой или неактуальной связи. */
    public static <T> TargetSnapshot<T> empty(long publishedEpoch, long revision) {
        return new TargetSnapshot<>(publishedEpoch, revision, List.of());
    }
}
