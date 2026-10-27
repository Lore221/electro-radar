package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-only данные одной цели, передаваемые {@link TargetProjection} во время запроса.
 *
 * <p>Статус наблюдения вычисляется только по радарам, доступным текущему потребителю.
 * {@link #liveResult()} содержит последнее разрешённое измерение и явно отличает его от
 * текущего наблюдения и замороженной серверной памяти.
 * Источники материализуются лениво, чтобы проекция, которой нужен только {@link TargetRef},
 * не создавала списки {@link RadarSource}. После возврата из проекции представление закрывается.</p>
 */
public final class TargetView {
    private final TargetRef target;
    private final boolean observingNow;
    private final LiveResult liveResult;
    private Supplier<List<RadarSource>> sourcesFactory;
    private List<RadarSource> sources;
    private boolean active = true;

    TargetView(
            TargetRef target,
            boolean observingNow,
            LiveResult liveResult,
            Supplier<List<RadarSource>> sourcesFactory
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.observingNow = observingNow;
        this.liveResult = Objects.requireNonNull(liveResult, "liveResult");
        this.sourcesFactory = Objects.requireNonNull(sourcesFactory, "sourcesFactory");
    }

    /** Возвращает полную идентичность цели: измерение, вид источника и UUID. */
    public TargetRef target() {
        ensureActive();
        return target;
    }

    /** Совместимое имя для текущего, уже опубликованного результата проверки доступных радаров. */
    @Deprecated
    public boolean detectedThisWindow() {
        ensureActive();
        return observingNow;
    }

    /** Возвращает, успешно ли доступный источник проверил цель в текущей серверной фазе. */
    public boolean observingNow() {
        ensureActive();
        return observingNow;
    }

    /** Возвращает true, если доступные радары только помнят цель после пропущенных сканов. */
    public boolean remembering() {
        ensureActive();
        return liveResult.availability() == TargetAvailability.AVAILABLE && !observingNow;
    }

    /**
     * Возвращает уже опубликованный общий результат получения живых данных.
     * {@code UNAVAILABLE} не удаляет обнаружения и не доказывает, что цель уничтожена.
     */
    public LiveResult liveResult() {
        ensureActive();
        return liveResult;
    }

    /**
     * Возвращает подробные сведения только об источниках, доступных этой регистрации.
     * Список создаётся при первом обращении и неизменяем; если проекция не вызывает этот
     * метод, ни одного объекта {@link RadarSource} для полного запроса не создаётся.
     */
    public List<RadarSource> sources() {
        ensureActive();
        if (sources == null) {
            sources = List.copyOf(sourcesFactory.get());
        }
        return sources;
    }

    /** Закрывает короткоживущее представление сразу после завершения проекции. */
    void expire() {
        active = false;
        sources = null;
        sourcesFactory = null;
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("TargetView is valid only while its projection is running");
        }
    }
}
