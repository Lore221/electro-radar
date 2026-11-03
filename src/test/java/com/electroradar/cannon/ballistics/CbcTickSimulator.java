package com.electroradar.cannon.ballistics;

/**
 * Независимый эталон: повторяет тик {@code AbstractCannonProjectile} без замкнутых формул.
 * Сначала проверяется отрезок {@code pos → pos + v + a}, затем {@code pos += v + a/2}, {@code v += a},
 * где {@code a = −k·v − g·ŷ}.
 */
final class CbcTickSimulator {
    private final ProjectileProfile profile;
    private Vector3 position;
    private Vector3 velocity;
    private int tick;

    CbcTickSimulator(ProjectileProfile profile, Vector3 direction) {
        this.profile = profile;
        this.position = direction.scale(profile.spawnOffset());
        this.velocity = direction.scale(profile.speed());
    }

    int tick() {
        return tick;
    }

    Vector3 sweepStart() {
        return position;
    }

    Vector3 sweepEnd() {
        return position.add(velocity).add(acceleration());
    }

    void advance() {
        Vector3 acceleration = acceleration();
        position = position.add(velocity).add(acceleration.scale(0.5D));
        velocity = velocity.add(acceleration);
        tick++;
    }

    void advanceTo(int target) {
        while (tick < target) advance();
    }

    /** Расстояние от точки до отрезка текущего тика. */
    double distanceToSweep(Vector3 point) {
        Vector3 start = sweepStart();
        Vector3 segment = sweepEnd().subtract(start);
        double length = segment.dot(segment);
        double t = length == 0.0D ? 0.0D : Math.min(1.0D, Math.max(0.0D, point.subtract(start).dot(segment) / length));
        return point.subtract(start.add(segment.scale(t))).length();
    }

    private Vector3 acceleration() {
        return velocity.scale(-profile.drag()).add(new Vector3(0.0D, -profile.gravity(), 0.0D));
    }
}
