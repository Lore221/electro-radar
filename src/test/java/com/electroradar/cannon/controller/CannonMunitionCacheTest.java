package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.mount.CannonMunition;

class CannonMunitionCacheTest {
    private static CannonMunition round(double speed) {
        return CannonMunition.ready(ProjectileProfile.of(speed, 2, 0.01, 0.05), 0.5);
    }

    @Test
    void shotInvalidationRereadsOnTheNextTick() {
        var cache = new CannonMunitionCache();
        Object cannon = new Object();
        var reads = new AtomicInteger();
        var first = cache.read(cannon, 2, 1000, false, () -> { reads.incrementAndGet(); return round(4); });
        assertSame(first, cache.read(cannon, 2, 1001, false, () -> { fail("cached read"); return null; }));
        cache.invalidate();
        var next = cache.read(cannon, 2, 1002, false, () -> { reads.incrementAndGet(); return round(8); });
        assertEquals(8, next.profile().orElseThrow().speed());
        assertEquals(2, reads.get());
    }

    @Test
    void replacementGeometryExpiryAndTimeResetRefreshTheProfile() {
        var cache = new CannonMunitionCache();
        Object cannon = new Object();
        var reads = new AtomicInteger();
        java.util.function.Supplier<CannonMunition> reader = () -> round(reads.incrementAndGet());
        cache.read(cannon, 2, 100, false, reader);
        cache.read(cannon, 2, 119, false, reader);
        assertEquals(1, reads.get());
        cache.read(cannon, 2, 120, false, reader);
        cache.read(cannon, 2, 10, false, reader);
        cache.read(cannon, 3, 11, false, reader);
        cache.read(new Object(), 3, 12, false, reader);
        assertEquals(5, reads.get());
    }

    @Test
    void fireCheckSeesAChangedOrRemovedRoundBeforePeriodicRefresh() {
        var cache = new CannonMunitionCache();
        Object cannon = new Object();
        var old = cache.read(cannon, 2, 100, false, () -> round(4));
        var fresh = cache.read(cannon, 2, 101, true, () -> round(8));
        assertNotEquals(old, fresh);
        assertSame(fresh, cache.read(cannon, 2, 102, false, () -> { fail("must reuse fresh profile"); return null; }));
        var empty = cache.read(cannon, 2, 103, true,
                () -> CannonMunition.of(CannonMunition.Status.NO_AMMUNITION));
        assertEquals(CannonMunition.Status.NO_AMMUNITION, empty.status());
    }
}
