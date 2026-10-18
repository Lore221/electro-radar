package com.electroradar.radar.network;

import java.util.Objects;

/** Компактная классификация, которую определил и передал наблюдающий радар. */
public record ObservationClassification(String id) {
    public static final ObservationClassification UNCLASSIFIED = new ObservationClassification("unclassified");

    public ObservationClassification {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || !id.equals(id.trim())) {
            throw new IllegalArgumentException("Classification id must be non-blank and trimmed");
        }
    }
}
