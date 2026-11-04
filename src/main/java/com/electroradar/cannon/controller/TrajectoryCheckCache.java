package com.electroradar.cannon.controller;

import java.util.function.Supplier;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

/** Короткий кеш только точного совпадения пути. Большая пушка перепроверяет свободный путь перед каждым выстрелом. */
final class TrajectoryCheckCache {
    private Key key;
    private TrajectoryTrace.Result result;
    private long checkedAt;

    Cached check(Key next, long now, boolean reuseClear, Supplier<TrajectoryTrace.Result> checker) {
        if (result != null && next.equals(key) && now >= checkedAt && now - checkedAt < 3
                && (reuseClear || result.status() != TrajectoryTrace.Status.CLEAR)) {
            return new Cached(result, true);
        }
        result = checker.get();
        checkedAt = now;
        key = next;
        return new Cached(result, false);
    }

    record Key(Object level, Object cannon, ProjectileProfile profile, Vector3 pivot,
               Vector3 direction, int tick, double fraction) {}
    record Cached(TrajectoryTrace.Result result, boolean reused) {}
}
