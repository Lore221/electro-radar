package com.electroradar.radar.visual;

/** Пресет следа источника; фактическую длительность выбирает клиент в своём конфиге. */
public enum RadarVisualFadeStyle {
    NONE(0, 0),
    SECTOR(1, 10),
    OVERVIEW(2, 40);

    private final int wireId;
    private final int defaultTicks;

    RadarVisualFadeStyle(int wireId, int defaultTicks) {
        this.wireId = wireId;
        this.defaultTicks = defaultTicks;
    }

    public int wireId() { return wireId; }
    public int defaultTicks() { return defaultTicks; }

    public static RadarVisualFadeStyle fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> NONE;
            case 1 -> SECTOR;
            case 2 -> OVERVIEW;
            default -> throw new IllegalArgumentException("Unknown radar visual fade style: " + wireId);
        };
    }
}
