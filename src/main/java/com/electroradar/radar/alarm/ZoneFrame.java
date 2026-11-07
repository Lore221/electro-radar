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
     * Проверяет максимальное смещение объёма при переходе к {@code other}. Максимум расстояния
     * между двумя аффинно преобразованными точками прямоугольника достигается в одной из вершин.
     */
    boolean close(ZoneFrame other, ProtectionBox volume, double distance) {
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = new Vector3(
                    (corner & 1) == 0 ? volume.min().x() : volume.max().x(),
                    (corner & 2) == 0 ? volume.min().y() : volume.max().y(),
                    (corner & 4) == 0 ? volume.min().z() : volume.max().z());
            if (toWorld(point).subtract(other.toWorld(point)).length() > distance) return false;
        }
        return true;
    }
}
