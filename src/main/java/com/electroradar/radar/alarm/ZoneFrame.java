package com.electroradar.radar.alarm;

import java.util.Objects;

import com.electroradar.cannon.ballistics.Vector3;

/**
 * Поза пространства зоны в мире: точка {@code localOrigin} её координат находится в
 * {@code worldOrigin}, а оси {@code x, y, z} — мировые образы единичных осей. Для зоны в
 * обычном мире это тождество, для зоны на движущейся структуре — текущая поза структуры.
 */
public record ZoneFrame(Vector3 localOrigin, Vector3 worldOrigin, Vector3 x, Vector3 y, Vector3 z) {
    public static final ZoneFrame IDENTITY = new ZoneFrame(Vector3.ZERO, Vector3.ZERO,
            new Vector3(1, 0, 0), new Vector3(0, 1, 0), new Vector3(0, 0, 1));

    public ZoneFrame {
        Objects.requireNonNull(localOrigin, "localOrigin");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        if (!(x.dot(x) > 1.0E-12D) || !(y.dot(y) > 1.0E-12D) || !(z.dot(z) > 1.0E-12D)) {
            throw new IllegalArgumentException("Degenerate zone frame");
        }
    }

    public boolean identity() {
        return equals(IDENTITY);
    }

    public Vector3 toWorld(Vector3 local) {
        Vector3 offset = local.subtract(localOrigin);
        return worldOrigin.add(x.scale(offset.x())).add(y.scale(offset.y())).add(z.scale(offset.z()));
    }

    /** Оси ортогональны, поэтому обратное преобразование — проекции на них. */
    public Vector3 toLocal(Vector3 world) {
        Vector3 offset = world.subtract(worldOrigin);
        return localOrigin.add(new Vector3(offset.dot(x) / x.dot(x), offset.dot(y) / y.dot(y), offset.dot(z) / z.dot(z)));
    }

    /**
     * Совпадает ли поза с {@code other} в пределах допуска: смещение одной и той же локальной
     * точки и отклонение осей.
     */
    boolean close(ZoneFrame other, Vector3 probe, double distance, double axisCosine) {
        return toWorld(probe).subtract(other.toWorld(probe)).length() <= distance
                && cosine(x, other.x) >= axisCosine && cosine(y, other.y) >= axisCosine
                && cosine(z, other.z) >= axisCosine;
    }

    private static double cosine(Vector3 a, Vector3 b) {
        return a.dot(b) / Math.sqrt(a.dot(a) * b.dot(b));
    }
}
