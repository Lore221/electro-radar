package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class InterceptionTargetSelectionTest {
    private static final UUID URGENT = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @ParameterizedTest
    @EnumSource(value = FireStatus.class, names = {"TRAJECTORY_BLOCKED", "TRAJECTORY_UNLOADED"})
    void obstructedUrgentShellDoesNotStarveAnotherThreat(FireStatus obstruction) {
        var selection = new InterceptionTargetSelection();
        var threats = Map.of(URGENT, 5, OTHER, 10);
        assertEquals(URGENT, selection.select(threats, 100));
        selection.recordOutcome(obstruction, 100);
        assertEquals(OTHER, selection.select(threats, 101));
    }

    @Test void rejectedShellBecomesEligibleAfterTwentyTicks() {
        var selection = new InterceptionTargetSelection();
        var threats = Map.of(URGENT, 5);
        assertEquals(URGENT, selection.select(threats, 100));
        selection.recordOutcome(FireStatus.TARGET_UNREACHABLE, 100);
        assertNull(selection.select(threats, 119));
        assertEquals(URGENT, selection.select(threats, 120));
    }

    @Test void aimingRetainsCurrentThreatAndRebindingClearsDeferrals() {
        var selection = new InterceptionTargetSelection();
        assertEquals(OTHER, selection.select(Map.of(OTHER, 10), 100));
        selection.recordOutcome(FireStatus.AIMING, 100);
        assertEquals(OTHER, selection.select(Map.of(URGENT, 1, OTHER, 10), 101));
        selection.recordOutcome(FireStatus.TARGET_UNREACHABLE, 101);
        selection.clear();
        assertEquals(OTHER, selection.select(Map.of(OTHER, 10), 102));
    }
}
