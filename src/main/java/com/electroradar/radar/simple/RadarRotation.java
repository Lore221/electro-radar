package com.electroradar.radar.simple;

import com.electroradar.radar.spatial.SpatialTransform;
import java.util.OptionalDouble;

/** Общая локальная фаза антенны и горизонтальная проекция луча в родительской системе. */
public record RadarRotation(long epochTick, double angleAtEpoch,
                            double rightX, double rightZ, double forwardX, double forwardZ) {
    public static final int RPM = 16;
    public static final int PERIOD_TICKS = 75;
    public static final int FADE_TICKS = 70;
    public static final double DEGREES_PER_TICK = 4.8;
    private static final double PROJECTION_EPSILON = 1e-12;

    public RadarRotation(long epochTick, double angleAtEpoch) {
        this(epochTick, angleAtEpoch, 1, 0, 0, 1);
    }

    public RadarRotation {
        if (!Double.isFinite(angleAtEpoch) || !Double.isFinite(rightX) || !Double.isFinite(rightZ)
                || !Double.isFinite(forwardX) || !Double.isFinite(forwardZ))
            throw new IllegalArgumentException("Non-finite radar phase or frame");
        angleAtEpoch = normalize(angleAtEpoch);
    }

    public RadarRotation inFrame(SpatialTransform frame) {
        return new RadarRotation(epochTick, angleAtEpoch, frame.x().x, frame.x().z, frame.z().x, frame.z().z);
    }

    public double localAngleDegrees(double tick) {
        return normalize(angleAtEpoch + ((tick - epochTick) % PERIOD_TICKS) * DEGREES_PER_TICK);
    }

    public double angleDegrees(double tick) {
        double radians = Math.toRadians(localAngleDegrees(tick));
        double x = rightX * Math.sin(radians) - forwardX * Math.cos(radians);
        double z = rightZ * Math.sin(radians) - forwardZ * Math.cos(radians);
        return Math.hypot(x, z) <= PROJECTION_EPSILON ? 0 : normalize(Math.toDegrees(Math.atan2(x, -z)));
    }

    public boolean hasHorizontalDirection(double tick) {
        double radians = Math.toRadians(localAngleDegrees(tick));
        return Math.hypot(rightX * Math.sin(radians) - forwardX * Math.cos(radians),
                rightZ * Math.sin(radians) - forwardZ * Math.cos(radians)) > PROJECTION_EPSILON;
    }

    /** При вертикальной плоскости ротора обратная проекция неоднозначна: контакты пропускаются. */
    public OptionalDouble localBearingDegrees(double worldBearing) {
        double determinant = rightX * forwardZ - forwardX * rightZ;
        if (Math.abs(determinant) <= PROJECTION_EPSILON || !Double.isFinite(worldBearing))
            return OptionalDouble.empty();
        double radians = Math.toRadians(worldBearing);
        double x = Math.sin(radians), z = -Math.cos(radians);
        double localX = (forwardZ * x - forwardX * z) / determinant;
        double localZ = (-rightZ * x + rightX * z) / determinant;
        return OptionalDouble.of(normalize(Math.toDegrees(Math.atan2(localX, -localZ))));
    }

    public static double normalize(double angle) { return (angle % 360 + 360) % 360; }
}
