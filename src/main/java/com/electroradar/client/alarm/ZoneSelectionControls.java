package com.electroradar.client.alarm;

/** Wheel rules; positive resize steps expand ZoneBounds. */
final class ZoneSelectionControls {
    private ZoneSelectionControls() { }

    static int range(int current, double delta) {
        if (!Double.isFinite(delta)) return current;
        return (int) Math.clamp(current + delta, 1, 100);
    }

    static int resizeStep(double delta, boolean inside) {
        if (!Double.isFinite(delta) || delta == 0) return 0;
        int amount = (int) Math.min(100, Math.ceil(Math.abs(delta)));
        return amount * (delta > 0 ? 1 : -1) * (inside ? 1 : -1);
    }
}
