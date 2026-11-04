package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.mount.CannonKind;

class FireTriggerTest {
    @Test
    void bigCannonGetsOneRisingEdgePerInterval() {
        FireTrigger trigger = new FireTrigger();
        StringBuilder pattern = new StringBuilder();
        for (int tick = 0; tick < 12; tick++) {
            pattern.append(trigger.update(CannonKind.BIG_CANNON, true, true) ? '1' : '0');
        }
        assertEquals("100001000010", pattern.toString());
        assertFalse(trigger.update(CannonKind.BIG_CANNON, false, true));
        assertTrue(trigger.update(CannonKind.BIG_CANNON, true, true), "после потери готовности импульс сразу");
    }

    @Test
    void autocannonHoldsThroughShortLossOnly() {
        FireTrigger trigger = new FireTrigger();
        assertTrue(trigger.update(CannonKind.AUTOCANNON, true, true));
        for (int tick = 0; tick < FireTrigger.AUTOCANNON_GRACE_TICKS; tick++) {
            assertTrue(trigger.update(CannonKind.AUTOCANNON, false, true), "очередь не рвётся на " + tick);
        }
        assertFalse(trigger.update(CannonKind.AUTOCANNON, false, true));
    }

    @Test
    void autocannonStopsAtOnceWhenTargetIsLost() {
        FireTrigger trigger = new FireTrigger();
        assertTrue(trigger.update(CannonKind.AUTOCANNON, true, true));
        assertFalse(trigger.update(CannonKind.AUTOCANNON, false, false));
    }
    @Test
    void mortarNeverSendsRedstoneAndClearsPreviousAutocannonBurst() {
        FireTrigger trigger = new FireTrigger();
        assertTrue(trigger.update(CannonKind.AUTOCANNON, true, true));
        for (int tick = 0; tick < 30; tick++) {
            assertFalse(trigger.update(CannonKind.DROP_MORTAR, true, true));
        }
        assertFalse(trigger.update(CannonKind.AUTOCANNON, false, true));
    }
    @Test
    void preflightGateCoversEverySignalIncludingAutocannonGrace() {
        for (var kind : CannonKind.values()) {
            var trigger = new FireTrigger();
            for (int tick = 0; tick < 80; tick++) {
                boolean ready = tick % 17 < 6;
                boolean held = tick % 23 < 20;
                boolean expected = trigger.wouldFire(kind, ready, held);
                assertEquals(expected, trigger.update(kind, ready, held), "kind " + kind + " tick " + tick);
            }
        }
    }

    @Test
    void obstacleStopsAutocannonGraceImmediately() {
        var trigger = new FireTrigger();
        assertTrue(trigger.update(CannonKind.AUTOCANNON, true, true));
        assertTrue(trigger.wouldFire(CannonKind.AUTOCANNON, false, true));
        assertFalse(trigger.update(CannonKind.AUTOCANNON, false, false));
        assertFalse(trigger.wouldFire(CannonKind.AUTOCANNON, false, true));
    }
}
