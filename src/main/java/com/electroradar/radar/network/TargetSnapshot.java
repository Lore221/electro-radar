package com.electroradar.radar.network;

import java.util.List;

/**
 * Неизменяемый результат запроса целей сети.
 *
 * <p>Список создаётся заново для конкретного запроса и не хранится в сетевом состоянии.
 * Пустой список не сам по себе признак ошибки доступа.</p>
 *
 * <p><b>Что гарантирует {@code contentVersion}.</b> Пока номер прежний, у ответа не меняется
 * видимое содержимое: состав целей и их источники, положение и тип, переход «наблюдается/в
 * памяти», {@code freezeTick} и длительность затухания, выбор, регистрации, активные сектора.
 * Потребитель, которому нужно только это (например, изображение монитора), может не
 * перечитывать цели, пока номер не изменился.</p>
 *
 * <p><b>Чего он не гарантирует.</b> Свежесть: у наблюдаемой цели {@link LiveResult#sampledTick()}
 * и {@link RadarSource#lastDetectedTick()} растут каждый тик, а номер при этом не меняется,
 * если цель стоит на месте. Потребитель, который проецирует эти времена, должен читать
 * каждый раз, когда они ему нужны, либо считать, что у наблюдаемой цели sample равен
 * текущему тику сервера.</p>
 *
 * @param <T> тип записей, выбранный {@link TargetProjection}
 * @param contentVersion версия видимого содержимого сети на момент ответа; 0 для закрытой связи
 * @param targets все уникальные цели, имеющие источник среди доступных радаров
 */
public record TargetSnapshot<T>(long contentVersion, List<T> targets) {
    public TargetSnapshot {
        if (contentVersion < 0) throw new IllegalArgumentException("Snapshot version must be non-negative");
        targets = List.copyOf(targets);
    }

    /** Пустой ответ для закрытой или неактуальной связи. */
    public static <T> TargetSnapshot<T> empty() {
        return new TargetSnapshot<>(0L, List.of());
    }
}
