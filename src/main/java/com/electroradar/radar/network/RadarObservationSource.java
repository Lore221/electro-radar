package com.electroradar.radar.network;

import java.util.Optional;

import com.electroradar.config.RadarServerConfig;

/**
 * Узкий серверный адаптер источника: сетевое ядро знает расписание и TargetRef, но не класс
 * контроллера, его BlockEntity или способ подготовки собственной геометрии.
 */
public interface RadarObservationSource {
    /** Готовит кешируемый immutable план на общей границе с одним атомарным снимком конфига. */
    Optional<RadarObservationPlan> createObservationPlan(RadarServerConfig.Snapshot parameters);

    /** Refreshes placement using the already selected configuration, without restarting the visit. */
    default Optional<RadarObservationPlan> refreshPlacement(RadarObservationPlan previous) {
        return Optional.of(previous);
    }

    /** Повторно проверяет готовность без загрузки чанков и без перестройки текущего плана. */
    boolean isReadyForObservation(RadarObservationPlan plan);
}
