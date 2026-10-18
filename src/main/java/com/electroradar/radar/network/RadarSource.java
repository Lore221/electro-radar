package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

/** Доступная потребителю связь цели с одним радаром и вычисленный возраст обнаружения. */
public record RadarSource(
        EndpointKey radar,
        long age,
        long lastDetectedTick,
        Optional<ObservationClassification> classification
) {
    public RadarSource {
        Objects.requireNonNull(radar, "radar");
        Objects.requireNonNull(classification, "classification");
        if (age < 0 || lastDetectedTick < 0) {
            throw new IllegalArgumentException("Radar source time values must be non-negative");
        }
    }

    /** Возвращает true, если радар снова обнаружил цель в последнем опубликованном окне. */
    public boolean detectedThisWindow() {
        return age == 0;
    }

    /** Возвращает true, если связь сохранена после одного или нескольких пропущенных проходов. */
    public boolean remembering() {
        return age > 0;
    }
}
