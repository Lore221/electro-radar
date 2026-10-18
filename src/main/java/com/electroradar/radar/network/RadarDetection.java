package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

/** Одна найденная радаром цель без координат и ссылок на игровые сущности. */
public record RadarDetection(TargetRef target, Optional<ObservationClassification> classification) {
    public RadarDetection {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(classification, "classification");
    }

    /** Создаёт обнаружение без дополнительной классификации радара. */
    public static RadarDetection of(TargetRef target) {
        return new RadarDetection(target, Optional.empty());
    }

    /** Создаёт обнаружение с классификацией, определённой конкретным радаром. */
    public static RadarDetection classified(TargetRef target, ObservationClassification classification) {
        return new RadarDetection(target, Optional.of(Objects.requireNonNull(classification, "classification")));
    }
}
