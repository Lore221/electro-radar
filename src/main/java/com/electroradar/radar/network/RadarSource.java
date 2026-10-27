package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

import com.electroradar.radar.visual.RadarVisualProfile;

/** Доступная потребителю связь цели с одним радаром и вычисленный возраст обнаружения. */
public record RadarSource(
        EndpointKey radar,
        int sectorIndex,
        int missedVisits,
        boolean observing,
        long lastDetectedTick,
        Optional<ObservationClassification> classification
) {
    public RadarSource {
        Objects.requireNonNull(radar, "radar");
        Objects.requireNonNull(classification, "classification");
        if (sectorIndex < 0 || sectorIndex >= RadarVisualProfile.MAX_SECTOR_COUNT
                || missedVisits < 0 || lastDetectedTick < 0) {
            throw new IllegalArgumentException("Radar source time values must be non-negative");
        }
    }

    /** Сохранённое подтверждение текущего геометрического сектора. */
    @Deprecated
    public boolean detectedThisWindow() {
        return observing;
    }

    /** Возвращает true, если цель остаётся в памяти, но сейчас этот радар её не наблюдает. */
    public boolean remembering() {
        return !observing;
    }
}
