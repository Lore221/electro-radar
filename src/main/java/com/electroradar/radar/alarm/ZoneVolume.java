package com.electroradar.radar.alarm;

import java.util.Objects;

import com.electroradar.cannon.ballistics.Vector3;

/**
 * Защитная зона в мире: прямоугольник в собственных координатах зоны, текущая поза этого
 * пространства и его линейная скорость в блоках за тик.
 *
 * <p>Зона в обычном мире неподвижна и совпадает с прямоугольником ({@link #fixed}). Зона на
 * движущейся структуре повёрнута её позой и движется вместе с ней. Прогноз считает поворот
 * постоянным, а сдвиг — равномерным: через {@code t} тиков зона смещена на {@code velocity·t}.</p>
 *
 * @param local прямоугольник в координатах зоны
 * @param frame поза пространства зоны в момент наблюдения
 * @param velocity скорость зоны в мире, блоков за тик
 */
public record ZoneVolume(ProtectionBox local, ZoneFrame frame, Vector3 velocity) {
    public ZoneVolume {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(velocity, "velocity");
    }

    public static ZoneVolume fixed(ProtectionBox box) {
        return new ZoneVolume(box, ZoneFrame.IDENTITY, Vector3.ZERO);
    }

    public ZoneVolume inflate(double reach) {
        return new ZoneVolume(local.inflate(reach), frame, velocity);
    }

    /**
     * Пересекает ли отрезок зону, если его концы — мировые точки в моменты {@code startTick} и
     * {@code endTick} от наблюдения. В координатах, движущихся вместе с зоной, это снова отрезок.
     */
    public boolean intersects(Vector3 start, double startTick, Vector3 end, double endTick) {
        return local.intersects(frame.toLocal(start.subtract(velocity.scale(startTick))),
                frame.toLocal(end.subtract(velocity.scale(endTick))));
    }

    /** Тот же отрезок без выделения векторов на каждом шаге прогноза. */
    boolean intersects(double sx, double sy, double sz, double startTick,
                       double ex, double ey, double ez, double endTick) {
        double ax = sx - velocity.x() * startTick - frame.worldOrigin().x();
        double ay = sy - velocity.y() * startTick - frame.worldOrigin().y();
        double az = sz - velocity.z() * startTick - frame.worldOrigin().z();
        double bx = ex - velocity.x() * endTick - frame.worldOrigin().x();
        double by = ey - velocity.y() * endTick - frame.worldOrigin().y();
        double bz = ez - velocity.z() * endTick - frame.worldOrigin().z();
        Vector3 origin = frame.localOrigin();
        return local.intersects(origin.x() + project(ax, ay, az, frame.x()),
                origin.y() + project(ax, ay, az, frame.y()), origin.z() + project(ax, ay, az, frame.z()),
                origin.x() + project(bx, by, bz, frame.x()), origin.y() + project(bx, by, bz, frame.y()),
                origin.z() + project(bx, by, bz, frame.z()));
    }

    private static double project(double x, double y, double z, Vector3 axis) {
        return (x * axis.x() + y * axis.y() + z * axis.z()) / axis.dot(axis);
    }

    /** Точка в момент наблюдения. */
    public boolean contains(Vector3 point) {
        return intersects(point, 0, point, 0);
    }

    /** Мировой прямоугольник, описанный вокруг зоны в момент наблюдения. */
    public ProtectionBox worldBounds() {
        if (frame.identity()) return local;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = frame.toWorld(new Vector3(
                    (corner & 1) == 0 ? local.min().x() : local.max().x(),
                    (corner & 2) == 0 ? local.min().y() : local.max().y(),
                    (corner & 4) == 0 ? local.min().z() : local.max().z()));
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        return new ProtectionBox(new Vector3(minX, minY, minZ), new Vector3(maxX, maxY, maxZ));
    }

    /**
     * Можно ли считать эту зону продолжением {@code earlier}, наблюдённой {@code elapsed} тиков
     * назад: тот же прямоугольник, поза совпадает с равномерно сдвинутой прежней, скорость та же.
     */
    boolean continues(ZoneVolume earlier, double elapsed, double reach, double distance, double speed) {
        if (!local.equals(earlier.local)) return false;
        if (frame.equals(earlier.frame) && velocity.equals(earlier.velocity) && velocity.equals(Vector3.ZERO)) return true;
        ZoneFrame expected = new ZoneFrame(earlier.frame.localOrigin(),
                earlier.frame.worldOrigin().add(earlier.velocity.scale(elapsed)),
                earlier.frame.x(), earlier.frame.y(), earlier.frame.z());
        return frame.close(expected, local.inflate(reach), distance)
                && velocity.subtract(earlier.velocity).length() <= speed;
    }
}
