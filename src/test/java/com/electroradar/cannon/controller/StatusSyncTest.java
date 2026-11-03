package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class StatusSyncTest {
    @Test
    void firstStatusIsImmediateAndLaterChangesAreThrottled() {
        var sync = new StatusSync();
        assertTrue(sync.update(FireStatus.NO_NETWORK, 1000));
        assertFalse(sync.update(FireStatus.NO_NETWORK, 1010));
        assertFalse(sync.update(FireStatus.AIMING, 1009));
        assertTrue(sync.update(FireStatus.READY, 1010));
        assertFalse(sync.update(FireStatus.READY, 1020));
    }

    @Test
    void clockResetDoesNotSuppressUpdates() {
        var sync = new StatusSync();
        assertTrue(sync.update(FireStatus.AIMING, 1000));
        assertTrue(sync.update(FireStatus.READY, 0));
        assertTrue(sync.update(FireStatus.AIMING, 10));
    }
}
