package com.electroradar.cannon.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class InterceptSearchTest {
    private static final AimConstraints MOUNT = AimConstraints.pitchRange(-45.0D, 85.0D);
    private static final double CLEARANCE = FlightHorizon.clearance(64.0D, -64, 0.5D);

    @Test
    void certifiedSkippingNeverLosesASolution() {
        Random random = new Random(11032026L);
        double[] drags = {0.0D, 0.001D, 0.01D, 0.05D};
        int skippedTicks = 0;
        int horizonTicks = 0;
        for (int i = 0; i < 400; i++) {
            ProjectileProfile profile = ProjectileProfile.of(2.0D + 10.0D * random.nextDouble(),
                    1.0D + 4.0D * random.nextDouble(), drags[i % drags.length], 0.02D + 0.08D * random.nextDouble());
            FlightCoefficients flight = FlightCoefficients.of(profile);
            FlightHorizon horizon = FlightHorizon.compute(flight, MOUNT.maxPitch(), CLEARANCE, 1000);
            TargetForecast target = i % 2 == 0 ? randomTarget(random) : constructedHit(random, flight, horizon);
            AimConstraints constraints = switch (i % 3) {
                case 0 -> MOUNT;
                case 1 -> MOUNT.withFamily(ArcFamily.LOW);
                default -> MOUNT.withFamily(ArcFamily.HIGH);
            };
            InterceptRequest request = InterceptRequest.of(flight, target, constraints, horizon);

            List<FireSolution> seen = new ArrayList<>();
            InterceptResult result = InterceptSearch.search(request, 0, Integer.MAX_VALUE, solution -> {
                seen.add(solution);
                return false;
            });
            assertEquals(InterceptResult.Status.NO_SOLUTION, result.status());
            assertEquals(bruteForceTicks(request), ticks(seen), "case " + i + " " + profile + " " + target);
            skippedTicks += horizon.lastTick() + 1 - result.kernelChecks();
            horizonTicks += horizon.lastTick() + 1;
        }
        // Перебор обязан экономить работу, а не только быть полным.
        assertTrue(skippedTicks > horizonTicks * 0.9D, "skipped " + skippedTicks + " of " + horizonTicks);
    }

    @Test
    void findsReviewCounterexamples() {
        // K2: оба минимума F0/F1 положительны, попадание только внутри отрезка.
        FlightCoefficients slow = FlightCoefficients.of(ProjectileProfile.of(2.0D, 2.0D, 0.0D, 0.05D));
        TargetForecast grazing = new TargetForecast(new Vector3(-103.77926666666667D, 81.5582D, 0.0D),
                new Vector3(3.354666666666667D, -0.041D, 0.0D), new Vector3(0.0D, -0.05D, 0.0D));
        assertEquals(49, firstTick(slow, grazing, AimConstraints.pitchRange(-90.0D, 90.0D), 100));

        // K3: тормозящая цель, при которой F‴ < 0 и ветка минимума n = 0 ошибалась.
        FlightCoefficients fast = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.0D, 0.05D));
        TargetForecast braking = new TargetForecast(new Vector3(20.0D, 0.0D, 0.0D),
                new Vector3(9.0D, 0.0D, 0.0D), new Vector3(-0.2D, -0.05D, 0.0D));
        assertEquals(17, firstTick(fast, braking, MOUNT, 100));
    }

    @Test
    void rejectedSolutionDoesNotStopTheSearch() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptRequest request = InterceptRequest.of(flight, TargetForecast.stationary(new Vector3(400.0D, 0.0D, 0.0D)),
                MOUNT, horizon);
        FireSolution first = InterceptSearch.search(request).solution().orElseThrow();
        // Потребитель (например, привод) отказывается от раннего решения — поиск продолжается.
        InterceptResult later = InterceptSearch.search(request, 0, Integer.MAX_VALUE,
                solution -> solution.tick() > first.tick() + 10);
        assertEquals(InterceptResult.Status.FOUND, later.status());
        assertTrue(later.solution().orElseThrow().pitch() > first.pitch());
    }

    @Test
    void resumesWithinBudget() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptRequest request = InterceptRequest.of(flight, new TargetForecast(new Vector3(300.0D, 20.0D, -90.0D),
                new Vector3(-0.4D, 0.0D, 0.3D), Vector3.ZERO), MOUNT.withFamily(ArcFamily.HIGH), horizon);
        InterceptResult whole = InterceptSearch.search(request);
        assertEquals(InterceptResult.Status.FOUND, whole.status());

        InterceptResult step = InterceptSearch.search(request, 0, 3, solution -> true);
        int calls = 1;
        while (step.status() == InterceptResult.Status.PENDING) {
            assertTrue(step.visitedTicks() <= 3);
            step = InterceptSearch.search(request, step.nextTick(), 3, solution -> true);
            calls++;
        }
        assertTrue(calls > 1);
        assertEquals(whole.solution(), step.solution());
    }

    @Test
    void provesAbsenceForUnreachableTarget() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptResult result = InterceptSearch.search(InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(5000.0D, 0.0D, 0.0D)), MOUNT, horizon));
        assertEquals(InterceptResult.Status.NO_SOLUTION, result.status());
        assertEquals(0, result.kernelChecks());
        assertTrue(result.visitedTicks() < 30, "visited " + result.visitedTicks());

        FlightCoefficients drifting = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.0D, 0.0D));
        InterceptResult unbounded = InterceptSearch.search(InterceptRequest.of(drifting,
                TargetForecast.stationary(new Vector3(0.0D, 0.0D, -5000.0D)), MOUNT,
                FlightHorizon.compute(drifting, 85.0D, CLEARANCE, 200)));
        assertEquals(InterceptResult.Status.NO_SOLUTION_WITHIN_LIMIT, unbounded.status());
    }

    @Test
    void hintIsCheckedFirstWithFreshForecast() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptRequest request = InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(400.0D, 0.0D, 0.0D)), MOUNT.withFamily(ArcFamily.HIGH), horizon);
        FireSolution cold = InterceptSearch.search(request).solution().orElseThrow();

        InterceptRequest moved = new InterceptRequest(flight, TargetForecast.stationary(new Vector3(400.05D, 0.0D, 0.0D)),
                request.constraints(), horizon, java.util.OptionalInt.of(cold.tick()));
        InterceptResult warm = InterceptSearch.search(moved);
        assertEquals(InterceptResult.Status.FOUND, warm.status());
        assertTrue(warm.visitedTicks() <= 3);
        assertTrue(Math.abs(warm.solution().orElseThrow().tick() - cold.tick()) <= 1);
    }

    /** Issue #13, п. 1: подсказка не должна съедать весь бюджет и зацикливать продолжение. */
    @Test
    void hintNeverStallsBudgetedContinuation() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptRequest unreachable = InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(5000.0D, 0.0D, 0.0D)), MOUNT, horizon);
        for (int hint : new int[] {0, 10, horizon.lastTick()}) {
            for (int budget = 1; budget <= 4; budget++) {
                InterceptRequest request = unreachable.withHint(hint);
                int next = 0;
                int calls = 0;
                InterceptResult result;
                do {
                    result = InterceptSearch.search(request, next, budget, solution -> true);
                    assertTrue(result.status() != InterceptResult.Status.PENDING || result.nextTick() > next,
                            "no progress: hint " + hint + " budget " + budget + " at " + next);
                    next = result.nextTick();
                    calls++;
                } while (result.status() == InterceptResult.Status.PENDING && calls < 10_000);
                assertEquals(InterceptResult.Status.NO_SOLUTION, result.status(), "hint " + hint + " budget " + budget);
            }
        }
        assertEquals(new InterceptResult(InterceptResult.Status.PENDING, java.util.Optional.empty(), 7, 0, 0),
                InterceptSearch.search(unreachable, 7, 0, solution -> true));
    }

    /** Issue #13, п. 2: снаряд с T = 60 не существует в отрезке 60. */
    @Test
    void lifetimeExcludesTheNonexistentLastSweep() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(2.0D, 2.0D, 0.0D, 0.0D).withLifetime(60));
        FlightHorizon horizon = FlightHorizon.compute(flight, 90.0D, CLEARANCE, 1000);
        // Отрезок 59 покрывает радиусы 120…122, отрезок 60 (122…124) снаряд уже не проходит.
        InterceptResult result = InterceptSearch.search(InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(123.0D, 0.0D, 0.0D)),
                AimConstraints.pitchRange(-90.0D, 90.0D), horizon));
        assertEquals(InterceptResult.Status.NO_SOLUTION, result.status());
        InterceptResult last = InterceptSearch.search(InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(121.0D, 0.0D, 0.0D)),
                AimConstraints.pitchRange(-90.0D, 90.0D), horizon));
        assertEquals(59, last.solution().orElseThrow().tick());
    }

    /** Issue #13, п. 3: подсказка задаёт явную политику, без неё действует гарантия самого раннего. */
    @Test
    void hintPrefersPreviousSolutionWhileColdSearchIsEarliest() {
        FlightCoefficients flight = FlightCoefficients.of(ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D));
        FlightHorizon horizon = FlightHorizon.compute(flight, 85.0D, CLEARANCE, 1000);
        InterceptRequest request = InterceptRequest.of(flight,
                TargetForecast.stationary(new Vector3(400.0D, 0.0D, 0.0D)), MOUNT, horizon);
        FireSolution earliest = InterceptSearch.search(request).solution().orElseThrow();
        assertEquals(bruteForceTicks(request).iterator().next(), earliest.tick());
        FireSolution preferred = InterceptSearch.search(request.withHint(198)).solution().orElseThrow();
        assertTrue(Math.abs(preferred.tick() - 198) <= 1, "hint " + preferred);
        assertTrue(preferred.tick() > earliest.tick());
        // Неподходящая подсказка не мешает найти самое раннее решение основным перебором.
        assertEquals(earliest, InterceptSearch.search(request.withHint(5)).solution().orElseThrow());
    }

    private static int firstTick(FlightCoefficients flight, TargetForecast target, AimConstraints constraints, int limit) {
        FlightHorizon horizon = FlightHorizon.compute(flight, constraints.maxPitch(), CLEARANCE, limit);
        InterceptRequest request = InterceptRequest.of(flight, target, constraints, horizon);
        InterceptResult result = InterceptSearch.search(request);
        assertEquals(InterceptResult.Status.FOUND, result.status());
        int tick = result.solution().orElseThrow().tick();
        assertEquals(bruteForceTicks(request).iterator().next(), tick);
        return tick;
    }

    private static Set<Integer> bruteForceTicks(InterceptRequest request) {
        Set<Integer> ticks = new TreeSet<>();
        for (int tick = 0; tick <= request.horizon().lastTick(); tick++) {
            Sweep sweep = request.flight().sweep(tick);
            if (!SweepIntersection.solve(sweep, request.target().positionAt(tick), request.constraints()).isEmpty()) {
                ticks.add(tick);
            }
        }
        return ticks;
    }

    private static Set<Integer> ticks(List<FireSolution> solutions) {
        Set<Integer> ticks = new TreeSet<>();
        for (FireSolution solution : solutions) ticks.add(solution.tick());
        return ticks;
    }

    private static TargetForecast randomTarget(Random random) {
        Vector3 position = new Vector3(random.nextDouble() * 800.0D - 400.0D, random.nextDouble() * 200.0D - 60.0D,
                random.nextDouble() * 800.0D - 400.0D);
        Vector3 velocity = new Vector3(random.nextGaussian(), 0.3D * random.nextGaussian(), random.nextGaussian());
        Vector3 acceleration = random.nextBoolean() ? Vector3.ZERO
                : new Vector3(0.01D * random.nextGaussian(), 0.01D * random.nextGaussian(), 0.01D * random.nextGaussian());
        return new TargetForecast(position, velocity, acceleration);
    }

    /** Движущаяся цель, которая в некоторый тик гарантированно лежит на отрезке столкновений. */
    private static TargetForecast constructedHit(Random random, FlightCoefficients flight, FlightHorizon horizon) {
        int tick = 1 + random.nextInt(Math.max(1, horizon.lastTick() - 1));
        double fraction = random.nextDouble();
        Sweep sweep = flight.sweep(tick);
        Vector3 direction = SweepIntersectionTest.randomDirection(random, -30.0D, 80.0D);
        Vector3 hit = direction.scale(sweep.radius() + fraction * sweep.step())
                .add(new Vector3(0.0D, -sweep.drop() - fraction * sweep.fall(), 0.0D));
        Vector3 velocity = new Vector3(random.nextGaussian(), 0.2D * random.nextGaussian(), random.nextGaussian());
        Vector3 acceleration = new Vector3(0.0D, random.nextBoolean() ? 0.0D : -flight.profile().gravity(), 0.0D);
        Vector3 start = hit.subtract(velocity.scale(tick)).subtract(acceleration.scale(0.5D * tick * tick));
        return new TargetForecast(start, velocity, acceleration);
    }
}
