package com.electroradar.client.monitor;

/** Один голубой цвет; прозрачность экранной плоскости настроена отдельно от пиксельного GUI. */
final class MonitorSectorPalette {
    static final int BASE_LINE_ARGB = 0x28D8FFE8;
    static final int ACTIVE_FILL_ARGB = 0x20D8FFE8;
    static final int ACTIVE_EDGE_ARGB = 0x80D8FFE8;
    /** Полупрозрачные линии BER видны с расстояния нескольких блоков на ярком фоне экрана. */
    static final int WORLD_BASE_LINE_ARGB = 0x80D8FFE8;
    static final int WORLD_ACTIVE_EDGE_ARGB = 0xD0D8FFE8;

    private MonitorSectorPalette() {
    }
}
