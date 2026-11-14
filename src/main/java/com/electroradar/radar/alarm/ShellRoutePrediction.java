package com.electroradar.radar.alarm;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;

/** Однократный прогноз движения и collision sweep CBC против зоны, без чтения блоков. */
public record ShellRoutePrediction(int firstDangerTick, int lastDangerTick, boolean complete) {
    public ShellRoutePrediction {
        if (firstDangerTick < -1 || lastDangerTick < firstDangerTick
                || (firstDangerTick == -1) != (lastDangerTick == -1)) {
            throw new IllegalArgumentException("Invalid route prediction");
        }
    }

    public boolean dangerousAfter(int updates) {
        return updates >= 0 && lastDangerTick >= updates;
    }

    public static ShellRoutePrediction predict(HostileShell shell, ProtectionBox zone, double reach, int limit) {
        return predict(shell, ZoneVolume.fixed(zone), reach, limit);
    }

    /**
     * Маршрут считается в координатах, движущихся вместе с зоной: к ходу {@code n} зона
     * смещается на {@code velocity·n}. Для неподвижной зоны это прежний расчёт в мире.
     */
    public static ShellRoutePrediction predict(HostileShell shell, ZoneVolume zone, double reach, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid prediction limit");
        ZoneVolume expanded = zone.inflate(reach);
        ProtectionBox bounds = expanded.worldBounds();
        Vector3 zoneVelocity = zone.velocity();
        double drag = shell.motion().drag();
        double g = shell.motion().gravity();
        int first = -1, last = -1;
        double x = shell.position().x(), y = shell.position().y(), z = shell.position().z();
        double vx = shell.velocity().x(), vy = shell.velocity().y(), vz = shell.velocity().z();
        boolean stationaryWorldZone = zone.frame().identity() && zoneVelocity.equals(Vector3.ZERO);
        for (int n = 0; n < limit; n++) {
            // Та же рекурсия CBC; примитивы исключают десятки временных векторов на ход.
            double ax = vx * -drag, ay = vy * -drag - g, az = vz * -drag;
            double nextVx = vx + ax, nextVy = vy + ay, nextVz = vz + az;
            double endX = x + nextVx, endY = y + nextVy, endZ = z + nextVz;
            double movedX = x + vx + ax * .5, movedY = y + vy + ay * .5, movedZ = z + vz + az * .5;
            if (!Double.isFinite(nextVx) || !Double.isFinite(nextVy) || !Double.isFinite(nextVz)
                    || !Double.isFinite(movedX) || !Double.isFinite(movedY) || !Double.isFinite(movedZ))
                throw new IllegalArgumentException("Non-finite predicted motion");
            boolean intersects = stationaryWorldZone
                    ? bounds.intersects(x, y, z, endX, endY, endZ) || bounds.intersects(x, y, z, movedX, movedY, movedZ)
                    : expanded.intersects(x, y, z, n, endX, endY, endZ, n + 1)
                            || expanded.intersects(x, y, z, n, movedX, movedY, movedZ, n + 1);
            if (intersects) {
                if (first == -1) first = n;
                last = n;
            }
            // Те же доказательства невозможности возвращения в зону, в мировых осях.
            if (past(x - zoneVelocity.x() * n, endX - zoneVelocity.x() * (n + 1), vx - zoneVelocity.x(),
                    limitX(vx, drag, zoneVelocity.x()), bounds.min().x(), bounds.max().x())
                    || past(z - zoneVelocity.z() * n, endZ - zoneVelocity.z() * (n + 1), vz - zoneVelocity.z(),
                    limitX(vz, drag, zoneVelocity.z()), bounds.min().z(), bounds.max().z())
                    || past(y - zoneVelocity.y() * n, endY - zoneVelocity.y() * (n + 1), vy - zoneVelocity.y(),
                    limitY(vy, drag, g, zoneVelocity.y()), bounds.min().y(), bounds.max().y())) {
                return new ShellRoutePrediction(first, last, true);
            }
            if (g == 0 && vx * vx + vy * vy + vz * vz == 0 && zoneVelocity.dot(zoneVelocity) == 0) {
                return new ShellRoutePrediction(first, first == -1 ? -1 : Integer.MAX_VALUE, true);
            }
            x = movedX; y = movedY; z = movedZ;
            vx = nextVx; vy = nextVy; vz = nextVz;
        }
        // Предел расчёта не доказывает отсутствия более поздних пересечений.
        return new ShellRoutePrediction(first, last, false);
    }

    /** Предел горизонтальной относительной скорости: сопротивление гасит собственную скорость снаряда. */
    private static double limitX(double velocity, double drag, double zoneVelocity) {
        return (drag > 0 ? 0 : velocity) - zoneVelocity;
    }

    private static double limitY(double velocity, double drag, double gravity, double zoneVelocity) {
        if (drag > 0) return -gravity / drag - zoneVelocity;
        return gravity > 0 ? Double.NEGATIVE_INFINITY : velocity - zoneVelocity;
    }

    /**
     * Снаряд ниже зоны и его относительная скорость по оси никогда не станет положительной,
     * или выше зоны и никогда не станет отрицательной.
     */
    private static boolean past(double start, double end, double speed, double limit, double min, double max) {
        return Math.max(start, end) < min && speed <= 0 && limit <= 0
                || Math.min(start, end) > max && speed >= 0 && limit >= 0;
    }
}
