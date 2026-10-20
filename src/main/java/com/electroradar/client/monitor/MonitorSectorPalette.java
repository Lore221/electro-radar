package com.electroradar.client.monitor;

/** Один голубой цвет; прозрачность экранной плоскости настроена отдельно от пиксельного GUI. */
final class MonitorSectorPalette {
    static final int BASE_LINE_ARGB = 0x28D8FFE8;
    static final int ACTIVE_FILL_ARGB = 0x20D8FFE8;
    static final int ACTIVE_EDGE_ARGB = 0x80D8FFE8;
    /** Короткое заполнение всей Coverage в GUI; итоговая alpha умножается на силу фазы. */
    static final int FLASH_FILL_ARGB = 0x3CD8FFE8;
    static final int FLASH_EDGE_ARGB = 0x88D8FFE8;
    /** Полупрозрачные линии BER видны с расстояния нескольких блоков на ярком фоне экрана. */
    static final int WORLD_BASE_LINE_ARGB = 0x80D8FFE8;
    static final int WORLD_ACTIVE_EDGE_ARGB = 0xD0D8FFE8;
    /** Более заметная широкая вспышка для плоскости блока; ширину задаёт world renderer. */
    static final int WORLD_FLASH_FILL_ARGB = 0x34D8FFE8;
    static final int WORLD_FLASH_EDGE_ARGB = 0xA0D8FFE8;

    private MonitorSectorPalette() {
    }

    /** Масштабирует только alpha цвета, сохраняя RGB-палитру и общий расчёт силы фазы. */
    static int scaleAlpha(int argb, float strength) {
        float bounded = Math.clamp(strength, 0.0F, 1.0F);
        int alpha = Math.max(0, Math.min(255,
                Math.round((argb >>> 24) * bounded)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
