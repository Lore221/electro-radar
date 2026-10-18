package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-only данные одной цели, передаваемые {@link TargetProjection} во время запроса.
 *
 * <p>Статус обнаружения вычисляется только по радарам, доступным текущему потребителю.
 * {@link #liveResult()} описывает отдельное получение живых параметров на границе окна:
 * доступные координаты не означают, что доступный радар обнаружил цель в этом окне.
 * Источники материализуются лениво, чтобы проекция, которой нужен только {@link TargetRef},
 * не создавала списки {@link RadarSource}. После возврата из проекции представление закрывается.</p>
 */
public final class TargetView {
    private final TargetRef target;
    private final boolean detectedThisWindow;
    private final LiveResult liveResult;
    private Supplier<List<RadarSource>> sourcesFactory;
    private List<RadarSource> sources;
    private boolean active = true;

    TargetView(
            TargetRef target,
            boolean detectedThisWindow,
            LiveResult liveResult,
            Supplier<List<RadarSource>> sourcesFactory
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.detectedThisWindow = detectedThisWindow;
        this.liveResult = Objects.requireNonNull(liveResult, "liveResult");
        this.sourcesFactory = Objects.requireNonNull(sourcesFactory, "sourcesFactory");
    }

    /** Возвращает полную идентичность цели: измерение, вид источника и UUID. */
    public TargetRef target() {
        ensureActive();
        return target;
    }

    /**
     * Возвращает, обнаружил ли хотя бы один доступный потребителю радар цель в последнем
     * опубликованном окне. Живое положение сущности на этот признак не влияет.
     */
    public boolean detectedThisWindow() {
        ensureActive();
        return detectedThisWindow;
    }

    /** Возвращает true, если доступные радары только помнят цель после пропущенных сканов. */
    public boolean remembering() {
        ensureActive();
        return !detectedThisWindow;
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
