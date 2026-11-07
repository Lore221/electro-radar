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
        Vector3 start = shell.position();
        Vector3 velocity = shell.velocity();
        Vector3 gravity = new Vector3(0, -g, 0);
        for (int n = 0; n < limit; n++) {
            // При последовательном проходе рекурсия дешевле четырёх вычислений expm1
            // на каждый ход. Замкнутая модель HostileShell остаётся проверкой кэша.
            Vector3 acceleration = velocity.scale(-drag).add(gravity);
            Vector3 nextVelocity = velocity.add(acceleration);
            Vector3 end = start.add(nextVelocity);
            Vector3 moved = start.add(velocity).add(acceleration.scale(.5));
            // Это виртуальная зона: учитываем и фактическое перемещение, и native CBC
            // collision sweep. Между концом sweep и следующей позицией возможен зазор.
            if (expanded.intersects(start, n, end, n + 1) || expanded.intersects(start, n, moved, n + 1)) {
                if (first == -1) first = n;
                last = n;
            }
            Vector3 relStart = start.subtract(zoneVelocity.scale(n));
            Vector3 relEnd = end.subtract(zoneVelocity.scale(n + 1));
            Vector3 relative = velocity.subtract(zoneVelocity);
            // Каждая компонента относительной скорости монотонно идёт к своему пределу:
            // горизонтальные — к −скорости зоны, вертикальная — к −g/k − скорости зоны.
            // Снаряд по ту сторону зоны, чья скорость к ней уже никогда не повернёт, не вернётся.
            if (past(relStart.x(), relEnd.x(), relative.x(), limitX(velocity.x(), drag, zoneVelocity.x()),
                    bounds.min().x(), bounds.max().x())
                    || past(relStart.z(), relEnd.z(), relative.z(), limitX(velocity.z(), drag, zoneVelocity.z()),
                    bounds.min().z(), bounds.max().z())
                    || past(relStart.y(), relEnd.y(), relative.y(), limitY(velocity.y(), drag, g, zoneVelocity.y()),
                    bounds.min().y(), bounds.max().y())) {
                return new ShellRoutePrediction(first, last, true);
            }
            if (g == 0 && velocity.length() == 0 && zoneVelocity.length() == 0) {
                return new ShellRoutePrediction(first, first == -1 ? -1 : Integer.MAX_VALUE, true);
            }
            start = moved;
            velocity = nextVelocity;
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
