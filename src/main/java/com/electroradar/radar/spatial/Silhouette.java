package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import com.electroradar.radar.visual.RadarVisualGeometry;

/** Неизменяемое изображение сохранённой формы и позы. Строится при первом чтении примитивов. */
public final class Silhouette {
    public static final int MAX_PRIMITIVES = 4096;
    public static final Silhouette EMPTY = new Silhouette(List.of(), List.of(), false);
    private volatile Data data;
    private Supplier<Silhouette> projection;

    public Silhouette(List<Quad> fills, List<RadarVisualGeometry.Segment> outline, boolean detailLimited) {
        fills = List.copyOf(fills); outline = List.copyOf(outline);
        if (fills.size() + outline.size() > MAX_PRIMITIVES) throw new IllegalArgumentException("Silhouette limit");
        data = new Data(fills, outline, detailLimited);
    }

    private Silhouette(Supplier<Silhouette> projection) { this.projection = Objects.requireNonNull(projection); }

    static Silhouette deferred(Supplier<Silhouette> projection) { return new Silhouette(projection); }

    public List<Quad> fills() { return resolve().fills; }
    public List<RadarVisualGeometry.Segment> outline() { return resolve().outline; }
    public boolean detailLimited() { return resolve().detailLimited; }

    /** Проверяет известное равенство без построения. false допускает лишнее обновление монитора. */
    public boolean sameView(Silhouette other) {
        if (this == other) return true;
        Data current = data;
        Data compared = other == null ? null : other.data;
        return current != null && compared != null && current.equals(compared);
    }

    private Data resolve() {
        Data current = data;
        if (current != null) return current;
        synchronized (this) {
            if (data == null) {
                Data resolved = Objects.requireNonNull(projection.get()).resolve();
                projection = null;
                data = resolved;
            }
            return data;
        }
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof Silhouette silhouette && resolve().equals(silhouette.resolve());
    }
    @Override public int hashCode() { return resolve().hashCode(); }
    @Override public String toString() { return "Silhouette[" + resolve() + "]"; }

    private record Data(List<Quad> fills, List<RadarVisualGeometry.Segment> outline, boolean detailLimited) { }
    public record Quad(RadarVisualGeometry.Point a, RadarVisualGeometry.Point b,
                       RadarVisualGeometry.Point c, RadarVisualGeometry.Point d) { }
}
