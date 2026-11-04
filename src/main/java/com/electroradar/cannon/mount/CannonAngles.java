package com.electroradar.cannon.mount;

/** Преобразование мирового угла в логические углы CBC для горизонтального/вертикального ствола. */
record CannonAngles(int verticalAxis, double minimum, double maximum) {
    double worldPitch(double logical) {
        return verticalAxis == 0 ? logical : verticalAxis * (90.0D - Math.abs(logical));
    }

    double minWorldPitch() {
        if (verticalAxis < 0 && minimum <= 0 && maximum >= 0) return -90;
        return verticalAxis == 0 ? minimum : Math.min(worldPitch(minimum), worldPitch(maximum));
    }

    double maxWorldPitch() {
        if (verticalAxis == 0) return maximum;
        if (verticalAxis > 0 && minimum <= 0 && maximum >= 0) return 90;
        return Math.max(worldPitch(minimum), worldPitch(maximum));
    }

    Pose logical(double yaw, double pitch) {
        if (verticalAxis == 0) return new Pose(yaw, Math.max(minimum, Math.min(maximum, pitch)));
        double magnitude = verticalAxis > 0 ? 90 - pitch : 90 + pitch;
        double logical = -magnitude;
        if (logical < minimum - 1e-8 || logical > maximum + 1e-8) logical = magnitude;
        logical = Math.max(minimum, Math.min(maximum, logical));
        return new Pose(yaw + (logical > 0 ? 180 : 0), logical);
    }

    record Pose(double yaw, double pitch) {}
}
