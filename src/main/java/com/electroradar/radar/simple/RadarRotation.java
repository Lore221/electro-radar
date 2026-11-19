package com.electroradar.radar.simple;

/** Общая фаза антенны и луча: градусы по часовой от севера при взгляде сверху. */
public record RadarRotation(long epochTick, double angleAtEpoch) {
    public static final int RPM = 16;
    public static final int PERIOD_TICKS = 75;
    public static final int FADE_TICKS = 70;
    public static final double DEGREES_PER_TICK = 4.8;

    public RadarRotation {
        if (!Double.isFinite(angleAtEpoch)) throw new IllegalArgumentException("Non-finite radar angle");
        angleAtEpoch = normalize(angleAtEpoch);
    }

    public double angleDegrees(double tick) {
        return normalize(angleAtEpoch + ((tick - epochTick) % PERIOD_TICKS) * DEGREES_PER_TICK);
    }

    public static double normalize(double angle) { return (angle % 360 + 360) % 360; }
}
