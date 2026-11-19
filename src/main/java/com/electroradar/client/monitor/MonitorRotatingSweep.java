package com.electroradar.client.monitor;

import com.electroradar.radar.simple.RadarRotation;
import com.electroradar.radar.visual.RadarVisualGeometry;

/** Общая геометрия физического луча и точный момент последнего пересечения. */
final class MonitorRotatingSweep {
    private MonitorRotatingSweep() { }

    static double angle(MonitorSectorAnimationState.ProfileFrame frame, double now) {
        return frame.profile().rotation().orElseThrow().angleDegrees(now + frame.serverTickOffset());
    }

    static RadarVisualGeometry.Segment beam(MonitorSectorAnimationState.ProfileFrame frame, double now) {
        var p = frame.profile();
        double radians = Math.toRadians(angle(frame, now));
        double radius = radius(frame);
        return new RadarVisualGeometry.Segment(new RadarVisualGeometry.Point(p.centerX(), p.centerZ()),
                new RadarVisualGeometry.Point(p.centerX() + Math.sin(radians) * radius,
                        p.centerZ() - Math.cos(radians) * radius));
    }

    private static double radius(MonitorSectorAnimationState.ProfileFrame frame) {
        var p = frame.profile();
        return p.coverage().outline().stream().mapToDouble(s ->
                Math.hypot(s.start().x() - p.centerX(), s.start().z() - p.centerZ())).max().orElse(0);
    }

    static double lastCrossing(MonitorSectorAnimationState.ProfileFrame frame, double x, double z, double now) {
        var p = frame.profile();
        double dx = x - p.centerX(), dz = z - p.centerZ();
        if (Math.hypot(dx, dz) > radius(frame) + 1e-8) return Double.NEGATIVE_INFINITY;
        double bearing = dx == 0 && dz == 0 ? 0
                : RadarRotation.normalize(Math.toDegrees(Math.atan2(dx, -dz)));
        double elapsed = RadarRotation.normalize(angle(frame, now) - bearing) / RadarRotation.DEGREES_PER_TICK;
        return now - elapsed;
    }
}
