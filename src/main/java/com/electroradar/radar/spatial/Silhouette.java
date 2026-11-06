package com.electroradar.radar.spatial;

import java.util.List;
import com.electroradar.radar.visual.RadarVisualGeometry;

/** Measured world XZ primitives. Immutable copies freeze both shape and pose together. */
public record Silhouette(List<Quad> fills, List<RadarVisualGeometry.Segment> outline, boolean detailLimited) {
    public static final int MAX_PRIMITIVES = 4096;
    public static final Silhouette EMPTY = new Silhouette(List.of(), List.of(), false);
    public Silhouette {
        fills = List.copyOf(fills); outline = List.copyOf(outline);
        if (fills.size() + outline.size() > MAX_PRIMITIVES) throw new IllegalArgumentException("Silhouette limit");
    }
    public record Quad(RadarVisualGeometry.Point a, RadarVisualGeometry.Point b,
                       RadarVisualGeometry.Point c, RadarVisualGeometry.Point d) { }
}
