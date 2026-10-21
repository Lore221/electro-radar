package com.electroradar.client.monitor;

import com.electroradar.config.RadarClientConfig;

/** Один голубой цвет; прозрачность экранной плоскости настроена отдельно от пиксельного GUI. */
final class MonitorSectorPalette {
    private MonitorSectorPalette() {
    }

    static int baseLine() { return RadarClientConfig.palette().guiBase(); }
    static int activeFill() { return RadarClientConfig.palette().guiActiveFill(); }
    static int activeEdge() { return RadarClientConfig.palette().guiActiveEdge(); }
    static int flashFill() { return RadarClientConfig.palette().guiFlashFill(); }
    static int flashEdge() { return RadarClientConfig.palette().guiFlashEdge(); }
    static int worldBaseLine() { return RadarClientConfig.palette().worldBase(); }
    static int worldActiveEdge() { return RadarClientConfig.palette().worldActiveEdge(); }
    static int worldFlashFill() { return RadarClientConfig.palette().worldFlashFill(); }
    static int worldFlashEdge() { return RadarClientConfig.palette().worldFlashEdge(); }

    /** Масштабирует только alpha цвета, сохраняя RGB-палитру и общий расчёт силы фазы. */
    static int scaleAlpha(int argb, float strength) {
        float bounded = Math.clamp(strength, 0.0F, 1.0F);
        int alpha = Math.max(0, Math.min(255,
                Math.round((argb >>> 24) * bounded)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
