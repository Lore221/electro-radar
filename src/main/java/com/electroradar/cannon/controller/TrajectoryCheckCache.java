package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.function.Supplier;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

/** Короткий кеш только точного совпадения пути. Большая пушка перепроверяет свободный путь перед каждым выстрелом. */
final class TrajectoryCheckCache {
    private static final long EXACT_TICKS = 3;
    /** Справочный результат миномёта: огонь по нему не разрешается, поэтому точное совпадение пути не нужно. */
    private static final long ADVISORY_TICKS = 10;
    private Key key;
    private TrajectoryTrace.Result result;
    private long checkedAt;

    Cached check(Key next, long now, boolean reuseClear, Supplier<TrajectoryTrace.Result> checker) {
        if (fresh(now, EXACT_TICKS) && next.equals(key)
                && (reuseClear || result.status() != TrajectoryTrace.Status.CLEAR)) {
            return new Cached(result, true);
        }
        return recheck(next, now, checker);
    }

    /** Тот же ствол и боеприпас: результат держится, пока ствол следует за целью. */
    Cached advisory(Key next, long now, Supplier<TrajectoryTrace.Result> checker) {
        if (fresh(now, ADVISORY_TICKS) && key.level() == next.level() && key.cannon() == next.cannon()
                && Objects.equals(key.profile(), next.profile())) {
            return new Cached(result, true);
        }
        return recheck(next, now, checker);
    }

    private boolean fresh(long now, long ticks) {
        return result != null && now >= checkedAt && now - checkedAt < ticks;
    }

    private Cached recheck(Key next, long now, Supplier<TrajectoryTrace.Result> checker) {
        result = checker.get();
        checkedAt = now;
        key = next;
        return new Cached(result, false);
    }

    record Key(Object level, Object cannon, ProjectileProfile profile, Vector3 pivot,
               Vector3 direction, int tick, double fraction) {}
    record Cached(TrajectoryTrace.Result result, boolean reused) {}
}
