package com.electroradar.client.monitor;

import com.electroradar.config.RadarClientConfig;

/** Один голубой цвет; прозрачность экранной плоскости настроена отдельно от пиксельного GUI. */
final class MonitorSectorPalette {
    private MonitorSectorPalette() {
    }

    static int baseLine() { return RadarClientConfig.palette().sector().gui().base(); }
    static int activeFill() { return RadarClientConfig.palette().sector().gui().activeFill(); }
    static int activeEdge() { return RadarClientConfig.palette().sector().gui().activeEdge(); }
    static int flashFill() { return RadarClientConfig.palette().sector().gui().flashFill(); }
    static int flashEdge() { return RadarClientConfig.palette().sector().gui().flashEdge(); }
    static int worldBaseLine() { return RadarClientConfig.palette().sector().world().base(); }
    static int worldActiveEdge() { return RadarClientConfig.palette().sector().world().activeEdge(); }
    static int worldFlashFill() { return RadarClientConfig.palette().sector().world().flashFill(); }
    static int worldFlashEdge() { return RadarClientConfig.palette().sector().world().flashEdge(); }

    static MonitorSectorMesh.Style guiStyle() {
        return new MonitorSectorMesh.Style(baseLine(), activeFill(), activeEdge(), flashFill(), flashEdge());
    }

    static MonitorSectorMesh.Style worldStyle() {
        return new MonitorSectorMesh.Style(worldBaseLine(), activeFill(), worldActiveEdge(),
                worldFlashFill(), worldFlashEdge());
    }

    /** Масштабирует только alpha цвета, сохраняя RGB-палитру и общий расчёт силы фазы. */
    static int scaleAlpha(int argb, float strength) {
        float bounded = Math.clamp(strength, 0.0F, 1.0F);
        int alpha = Math.max(0, Math.min(255,
                Math.round((argb >>> 24) * bounded)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
