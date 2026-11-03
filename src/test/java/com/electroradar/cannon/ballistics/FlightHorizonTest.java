package com.electroradar.cannon.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlightHorizonTest {
    /** Пушка на y = 64, низ мира −64, запас хитбокса 0,5: как в CBC_AIMING_HORIZON.md. */
    private static final double CLEARANCE = FlightHorizon.clearance(64.0D, -64, 0.5D);
    private static final int LIMIT = 100_000;

    @ParameterizedTest
    @CsvSource({
            "2, 0.01, 90, 146",
            "4, 0.01, 90, 192",
            "8, 0.01, 90, 282",
            "8, 0.01, 85, 281",
            "8, 0.01, 60, 258",
            "8, 0.0, 90, 343",
            "8, 0.05, 90, 369",
            "12, 0.01, 90, 368",
    })
    void matchesPublishedTable(double speed, double drag, double maxPitch, int lastTick) {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(speed, 2.0D, drag, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, maxPitch, CLEARANCE, LIMIT);
        assertEquals(new FlightHorizon(lastTick, FlightHorizon.Bound.KILL_PLANE), horizon);
    }

    @Test
    void projectileNeverReturnsAboveKillPlane() {
        ProjectileProfile profile = ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D);
        FlightCoefficients flight = FlightCoefficients.of(profile);
        FlightHorizon horizon = FlightHorizon.compute(flight, 90.0D, CLEARANCE, LIMIT);
        // Прямая симуляция всех направлений по углу места: ни одна точка отрезка после горизонта
        // не поднимается выше плоскости удаления, а в последнем тике вертикальный выстрел ещё выше.
        for (int degrees = -90; degrees <= 90; degrees += 5) {
            double pitch = Math.toRadians(degrees);
            CbcTickSimulator simulator = new CbcTickSimulator(profile, new Vector3(Math.cos(pitch), Math.sin(pitch), 0.0D));
            simulator.advanceTo(horizon.lastTick() + 1);
            for (int tick = horizon.lastTick() + 1; tick < horizon.lastTick() + 3000; tick++) {
                double highest = Math.max(simulator.sweepStart().y(), simulator.sweepEnd().y());
                assertTrue(highest < -CLEARANCE, "pitch " + degrees + " tick " + tick);
                simulator.advance();
            }
        }
        CbcTickSimulator vertical = new CbcTickSimulator(profile, new Vector3(0.0D, 1.0D, 0.0D));
        vertical.advanceTo(horizon.lastTick());
        assertTrue(Math.max(vertical.sweepStart().y(), vertical.sweepEnd().y()) >= -CLEARANCE);
    }

    @Test
    void zeroGravityStopsAtMinimumSweepLength() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.0D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 90.0D, CLEARANCE, LIMIT);
        assertEquals(FlightHorizon.Bound.MIN_SWEEP_LENGTH, horizon.bound());
        assertTrue(flight.sweep(horizon.lastTick()).step() >= SweepIntersection.MIN_SWEEP_LENGTH);
        assertTrue(flight.sweep(horizon.lastTick() + 1).step() < SweepIntersection.MIN_SWEEP_LENGTH);
    }

    @Test
    void lifetimeAndLimitBoundTheSearch() {
        FlightCoefficients autocannon = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D).withLifetime(60));
        assertEquals(new FlightHorizon(60, FlightHorizon.Bound.LIFETIME),
                FlightHorizon.compute(autocannon, 90.0D, CLEARANCE, LIMIT));

        FlightCoefficients drifting = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.0D, 0.0D));
        FlightHorizon unbounded = FlightHorizon.compute(drifting, 90.0D, CLEARANCE, 500);
        assertEquals(new FlightHorizon(500, FlightHorizon.Bound.LIMIT), unbounded);
        assertFalse(unbounded.proven());
    }
}
