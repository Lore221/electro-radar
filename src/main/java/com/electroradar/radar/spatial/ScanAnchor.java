package com.electroradar.radar.spatial;

import java.util.Objects;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;

/** Измеренная точка пересечения физической формы с обзором конкретного радара. */
public record ScanAnchor(EndpointKey radar, Point point) {
    public ScanAnchor {
        Objects.requireNonNull(radar, "radar");
        Objects.requireNonNull(point, "point");
    }
}
