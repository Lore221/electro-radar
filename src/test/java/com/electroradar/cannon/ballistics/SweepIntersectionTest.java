package com.electroradar.cannon.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SweepIntersectionTest {
    private static final AimConstraints FULL = AimConstraints.pitchRange(-90.0D, 90.0D);

    @Test
    void recoversConstructedHitsAndPassesForwardCheck() {
        Random random = new Random(20261103L);
        double[] drags = {0.0D, 1.0E-6D, 0.01D, 0.05D};
        for (int i = 0; i < 2000; i++) {
            ProjectileProfile profile = ProjectileProfile.of(2.0D + 10.0D * random.nextDouble(),
                    1.0D + 4.0D * random.nextDouble(), drags[i % drags.length], 0.02D + 0.08D * random.nextDouble());
            FlightCoefficients flight = FlightCoefficients.of(profile);
            int tick = random.nextInt(300);
            double fraction = random.nextDouble();
            Vector3 direction = randomDirection(random, -40.0D, 80.0D);
            Sweep sweep = flight.sweep(tick);
            double reach = sweep.radius() + fraction * sweep.step();
            Vector3 target = direction.scale(reach).add(new Vector3(0.0D, -sweep.drop() - fraction * sweep.fall(), 0.0D));

            List<FireSolution> solutions = SweepIntersection.solve(sweep, target, FULL);
            FireSolution match = solutions.stream()
                    .filter(solution -> solution.direction().subtract(direction).length() < 1.0E-7D)
                    .findFirst().orElseThrow(() -> new AssertionError("Constructed hit lost: " + profile + " tick " + tick));
            assertEquals(fraction, match.fraction(), 1.0E-6D);
            for (FireSolution solution : solutions) {
                CbcTickSimulator simulator = new CbcTickSimulator(profile, solution.direction());
                simulator.advanceTo(tick);
                assertTrue(simulator.distanceToSweep(target) < 1.0E-7D * Math.max(1.0D, target.length()),
                        "Forward check failed for " + solution);
            }
        }
    }

    @Test
    void separatesInteriorRootsIntoLowAndHigh() {
        // Цель внутри выступа промежуточных сфер, снаружи обеих крайних (случай K1 рецензии).
        ProjectileProfile profile = ProjectileProfile.of(2.0D, 2.0D, 0.01D, 0.05D);
        Sweep sweep = FlightCoefficients.of(profile).sweep(60);
        double reach = sweep.radius() + 0.5D * sweep.step();
        double uy = sweep.step() / sweep.fall();
        Vector3 target = new Vector3(reach * Math.sqrt(1.0D - uy * uy) * (1.0D - 1.0E-7D),
                reach * uy * (1.0D - 1.0E-7D) - sweep.drop() - 0.5D * sweep.fall(), 0.0D);

        List<FireSolution> solutions = SweepIntersection.solve(sweep, target, FULL);
        assertEquals(2, solutions.size());
        FireSolution low = solutions.get(0);
        FireSolution high = solutions.get(1);
        assertEquals(ArcBranch.LOW, low.branch());
        assertEquals(ArcBranch.HIGH, high.branch());
        assertTrue(low.fraction() < high.fraction());
        assertTrue(low.pitch() < high.pitch());
        assertEquals(List.of(low), SweepIntersection.solve(sweep, target, FULL.withFamily(ArcFamily.LOW)));
        assertEquals(List.of(high), SweepIntersection.solve(sweep, target, FULL.withFamily(ArcFamily.HIGH)));
    }

    @Test
    void lowBranchIsFlatterForStaticTarget() {
        ProjectileProfile profile = ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D);
        FlightCoefficients flight = FlightCoefficients.of(profile);
        Vector3 target = new Vector3(400.0D, 0.0D, 0.0D);
        double lowPitch = Double.NaN;
        double highPitch = Double.NaN;
        for (int tick = 0; tick < 600; tick++) {
            for (FireSolution solution : SweepIntersection.solve(flight.sweep(tick), target, FULL)) {
                if (solution.branch() == ArcBranch.LOW && Double.isNaN(lowPitch)) lowPitch = solution.pitch();
                if (solution.branch() == ArcBranch.HIGH && Double.isNaN(highPitch)) highPitch = solution.pitch();
            }
        }
        assertTrue(lowPitch < highPitch, "low " + lowPitch + " high " + highPitch);
    }

    @Test
    void respectsPitchLimitsAndYawConvention() {
        ProjectileProfile profile = ProjectileProfile.of(8.0D, 2.0D, 0.0D, 0.05D);
        Sweep sweep = FlightCoefficients.of(profile).sweep(20);
        Vector3 direction = new Vector3(-0.6D, 0.0D, 0.8D);
        Vector3 target = direction.scale(sweep.radius()).add(new Vector3(0.0D, -sweep.drop(), 0.0D));
        FireSolution solution = SweepIntersection.solve(sweep, target, FULL).stream()
                .filter(candidate -> candidate.fraction() < 1.0E-9D).findFirst().orElseThrow();
        assertEquals(Math.toDegrees(Math.atan2(0.6D, 0.8D)), solution.yaw(), 1.0E-9D);
        assertEquals(0.0D, solution.pitch(), 1.0E-9D);
        assertTrue(SweepIntersection.solve(sweep, target, AimConstraints.pitchRange(5.0D, 90.0D)).stream()
                .noneMatch(candidate -> candidate.fraction() < 1.0E-9D));
    }

    @Test
    void ignoresSweepsShorterThanCbcThreshold() {
        // Без гравитации и с сильным сопротивлением шаг быстро падает ниже 0,01.
        ProjectileProfile profile = ProjectileProfile.of(1.0D, 1.0D, 0.5D, 0.0D);
        FlightCoefficients flight = FlightCoefficients.of(profile);
        int tick = 10;
        Sweep sweep = flight.sweep(tick);
        assertTrue(sweep.step() < SweepIntersection.MIN_SWEEP_LENGTH);
        Vector3 target = new Vector3(sweep.radius() + 0.5D * sweep.step(), 0.0D, 0.0D);
        assertTrue(SweepIntersection.solve(sweep, target, FULL).isEmpty());
    }

    static Vector3 randomDirection(Random random, double minPitch, double maxPitch) {
        double pitch = Math.toRadians(minPitch + (maxPitch - minPitch) * random.nextDouble());
        double yaw = 2.0D * Math.PI * random.nextDouble();
        return new Vector3(Math.cos(pitch) * Math.cos(yaw), Math.sin(pitch), Math.cos(pitch) * Math.sin(yaw));
    }
}
