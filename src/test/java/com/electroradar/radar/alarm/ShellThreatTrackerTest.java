package com.electroradar.radar.alarm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;
import com.electroradar.cannon.intercept.LinearDragMotion;

class ShellThreatTrackerTest {
    private static final ProtectionBox ZONE = new ProtectionBox(new Vector3(-2, -10, 10), new Vector3(2, 10, 20));

    @Test void reusesPredictionForNormalFlightAndExpiresPastThreat() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        UUID id = UUID.randomUUID();
        HostileShell shell = new HostileShell(Vector3.ZERO, new Vector3(0, 0, 2), new LinearDragMotion(.01, .025));
        for (int age = 0; age < 30; age++) {
            tracker.refresh(List.of(observe(id, age, shell)), ZONE);
            assertEquals(1, tracker.predictionCount());
            if (age == 0) assertEquals(Set.of(id), tracker.dangerousProjectiles());
        }
        assertTrue(tracker.dangerousProjectiles().isEmpty());
    }

    @Test void removesTargetAsSoonAsItIsNoLongerAuthorizedByNetwork() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = incoming(UUID.randomUUID());
        tracker.refresh(List.of(sample), ZONE);
        Set<UUID> snapshot = tracker.dangerousProjectiles();
        assertEquals(Set.of(sample.id()), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        tracker.refresh(List.of(), ZONE);
        assertTrue(tracker.dangerousProjectiles().isEmpty());
    }

    @Test void reclassifiesAfterRicochetOrProfileChange() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = incoming(UUID.randomUUID());
        tracker.refresh(List.of(sample), ZONE);
        HostileShell redirected = new HostileShell(Vector3.ZERO, new Vector3(0, 0, -2), new LinearDragMotion(.01, .025));
        tracker.refresh(List.of(new ShellThreatTracker.Observation(sample.id(), 1, redirected, .25)), ZONE);
        assertTrue(tracker.dangerousProjectiles().isEmpty());
        assertEquals(2, tracker.predictionCount());
        tracker.refresh(List.of(new ShellThreatTracker.Observation(sample.id(), 2,
                new HostileShell(redirected.position(), redirected.velocity(), new LinearDragMotion(.02, .025)), .25)), ZONE);
        assertEquals(3, tracker.predictionCount());
    }

    @Test void differentZoneReclassifiesTheSameUuid() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = incoming(UUID.randomUUID());
        tracker.refresh(List.of(sample), ZONE);
        tracker.refresh(List.of(sample), new ProtectionBox(new Vector3(100, 0, 0), new Vector3(101, 1, 1)));
        assertTrue(tracker.dangerousProjectiles().isEmpty());
        assertEquals(2, tracker.predictionCount());
    }

    @Test void budgetSpreadsNewTargetsAcrossTicksWithoutCallingThemSafe() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var observations = new ArrayList<ShellThreatTracker.Observation>();
        for (int i = 0; i < 12; i++) observations.add(incoming(UUID.randomUUID()));
        tracker.refresh(observations, ZONE);
        assertEquals(8, tracker.predictionCount());
        assertEquals(4, tracker.incompleteCount());
        tracker.refresh(observations, ZONE);
        assertEquals(12, tracker.predictionCount());
        assertEquals(0, tracker.incompleteCount());
        assertEquals(12, tracker.dangerousProjectiles().size());
    }

    @Test void clearDropsAllResultsAndSupportsRebinding() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = incoming(UUID.randomUUID());
        tracker.refresh(List.of(sample), ZONE);
        tracker.clear();
        assertTrue(tracker.dangerousProjectiles().isEmpty());
        tracker.refresh(List.of(sample), ZONE);
        assertEquals(Set.of(sample.id()), tracker.dangerousProjectiles());
    }

    @Test void loadingSameUuidWithResetAgeStartsANewModel() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = incoming(UUID.randomUUID());
        tracker.refresh(List.of(new ShellThreatTracker.Observation(sample.id(), 100, sample.shell(), .25)), ZONE);
        tracker.refresh(List.of(sample), ZONE);
        assertEquals(2, tracker.predictionCount());
    }

    @Test void nonComputableFutureDoesNotCrashAndPreservesKnownCurrentThreat() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        var sample = new ShellThreatTracker.Observation(UUID.randomUUID(), 0,
                new HostileShell(Vector3.ZERO, new Vector3(0, 1e308, 0), new LinearDragMotion(0, 0)), 0);
        ProtectionBox zone = new ProtectionBox(new Vector3(-1, -1, -1), new Vector3(1, 1, 1));
        assertDoesNotThrow(() -> tracker.refresh(List.of(sample), zone));
        assertEquals(1, tracker.incompleteCount());
        assertEquals(Set.of(sample.id()), tracker.dangerousProjectiles());
    }

    @Test void limitedForecastIsRecomputedAsTheShellApproachesTheUnexaminedFuture() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        UUID id = UUID.randomUUID();
        HostileShell shell = new HostileShell(Vector3.ZERO, new Vector3(0, 0, 1), new LinearDragMotion(0, 0));
        ProtectionBox zone = new ProtectionBox(new Vector3(-1, -1, 5_000), new Vector3(1, 1, 5_001));
        tracker.refresh(List.of(observe(id, 0, shell)), zone);
        assertEquals(1, tracker.incompleteCount());
        assertTrue(tracker.dangerousProjectiles().isEmpty());
        tracker.refresh(List.of(observe(id, 2_048, shell)), zone);
        assertEquals(2, tracker.predictionCount());
        assertEquals(Set.of(id), tracker.dangerousProjectiles());
    }

    @Test void currentIntersectionDoesNotWaitForForecastBudgetOrPhysicsSupport() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        UUID unsupported = UUID.randomUUID();
        tracker.refresh(List.of(), ZONE, Set.of(unsupported));
        assertEquals(Set.of(unsupported), tracker.dangerousProjectiles());
        assertEquals(0, tracker.predictionCount());
        tracker.refresh(List.of(), ZONE, Set.of());
        assertTrue(tracker.dangerousProjectiles().isEmpty());
        var inside = new ProtectionBox(new Vector3(-1, -1, -1), new Vector3(1, 1, 1));
        var observations = new ArrayList<ShellThreatTracker.Observation>();
        for (int i = 0; i < 12; i++) observations.add(incoming(UUID.randomUUID()));
        tracker.refresh(observations, inside);
        assertEquals(12, tracker.dangerousProjectiles().size());
        assertEquals(8, tracker.predictionCount());
    }

    private static ShellThreatTracker.Observation incoming(UUID id) {
        return new ShellThreatTracker.Observation(id, 0,
                new HostileShell(Vector3.ZERO, new Vector3(0, 0, 2), new LinearDragMotion(.01, .025)), .25);
    }

    private static ShellThreatTracker.Observation observe(UUID id, int age, HostileShell initial) {
        return new ShellThreatTracker.Observation(id, age,
                new HostileShell(initial.positionAfter(age), initial.velocityAfter(age), initial.motion()), .25);
    }
}
