package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

class TrajectoryCheckCacheTest {
    private static final Object LEVEL = new Object();
    private static final Object CANNON = new Object();
    private static TrajectoryCheckCache.Key key(Vector3 origin, double fraction) {
        return new TrajectoryCheckCache.Key(LEVEL, CANNON, ProjectileProfile.of(8, 2, .01, .05), origin,
                new Vector3(1, 0, 0), 20, fraction);
    }
    private static TrajectoryTrace.Result result(TrajectoryTrace.Status status) {
        return new TrajectoryTrace.Result(status, 20, Vector3.ZERO, 21);
    }

    @Test void autocannonReusesOnlyAnIdenticalPathForAtMostThreeTicks() {
        var cache = new TrajectoryCheckCache();
        var calls = new AtomicInteger();
        java.util.function.Supplier<TrajectoryTrace.Result> check = () -> { calls.incrementAndGet(); return result(TrajectoryTrace.Status.CLEAR); };
        var key = key(Vector3.ZERO, .5);
        assertFalse(cache.check(key, 100, true, check).reused());
        assertTrue(cache.check(key, 102, true, check).reused());
        assertFalse(cache.check(key, 103, true, check).reused());
        assertFalse(cache.check(key(new Vector3(.000001, 0, 0), .5), 104, true, check).reused());
        assertFalse(cache.check(key(new Vector3(.000001, 0, 0), .6), 105, true, check).reused());
        assertFalse(cache.check(key, 1, true, check).reused());
        assertEquals(5, calls.get());
    }

    @Test void bigCannonAlwaysRechecksClearPathAndBlockedPathsExpire() {
        var cache = new TrajectoryCheckCache();
        var key = key(Vector3.ZERO, .5);
        assertFalse(cache.check(key, 100, false, () -> result(TrajectoryTrace.Status.CLEAR)).reused());
        assertFalse(cache.check(key, 101, false, () -> result(TrajectoryTrace.Status.BLOCKED)).reused());
        assertTrue(cache.check(key, 102, false, () -> { throw new AssertionError("blocked cache must be reused"); }).reused());
        var expired = cache.check(key, 104, false, () -> result(TrajectoryTrace.Status.CLEAR));
        assertFalse(expired.reused());
        assertEquals(TrajectoryTrace.Status.CLEAR, expired.result().status());
    }
}
