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
        if (limit < 1) throw new IllegalArgumentException("Invalid prediction limit");
        ProtectionBox expanded = zone.inflate(reach);
        int first = -1, last = -1;
        Vector3 start = shell.position();
        Vector3 velocity = shell.velocity();
        Vector3 gravity = new Vector3(0, -shell.motion().gravity(), 0);
        for (int n = 0; n < limit; n++) {
            // При последовательном проходе рекурсия дешевле четырёх вычислений expm1
            // на каждый ход. Замкнутая модель HostileShell остаётся проверкой кэша.
            Vector3 acceleration = velocity.scale(-shell.motion().drag()).add(gravity);
            Vector3 nextVelocity = velocity.add(acceleration);
            Vector3 end = start.add(nextVelocity);
            Vector3 moved = start.add(velocity).add(acceleration.scale(.5));
            // Это виртуальная зона: учитываем и фактическое перемещение, и native CBC
            // collision sweep. Между концом sweep и следующей позицией возможен зазор.
            if (expanded.intersects(start, end) || expanded.intersects(start, moved)) {
                if (first == -1) first = n;
                last = n;
            }
            // Горизонтальные компоненты при линейном drag не меняют знак.
            if (past(start.x(), end.x(), velocity.x(), expanded.min().x(), expanded.max().x())
                    || past(start.z(), end.z(), velocity.z(), expanded.min().z(), expanded.max().z())
                    // Ниже зоны при движении вниз вертикаль уже не вернётся.
                    || Math.max(start.y(), end.y()) < expanded.min().y() && velocity.y() <= 0
                    || shell.motion().gravity() == 0
                    && past(start.y(), end.y(), velocity.y(), expanded.min().y(), expanded.max().y())) {
                return new ShellRoutePrediction(first, last, true);
            }
            if (shell.motion().gravity() == 0 && velocity.length() == 0) {
                return new ShellRoutePrediction(first, first == -1 ? -1 : Integer.MAX_VALUE, true);
            }
            start = moved;
            velocity = nextVelocity;
        }
        // Предел расчёта не доказывает отсутствие более поздних пересечений.
        return new ShellRoutePrediction(first, last, false);
    }

    private static boolean past(double start, double end, double speed, double min, double max) {
        return Math.max(start, end) < min && speed <= 0 || Math.min(start, end) > max && speed >= 0;
    }
}
