package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.ArcFamily;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.Vector3;

class InterceptPlannerTest {
    private static final AimConstraints MOUNT = AimConstraints.pitchRange(-30.0D, 60.0D);

    /**
     * Сквозная проверка соглашения о времени: снаряд, впервые обновлённый в {@code T + d}, по найденному
     * направлению в своём тике {@code n} проходит через положение цели в {@code T + d + n}; d = 1 (прямой
     * fireShot) и 2 (миномёт).
     * Эталон — потиковая рекурсия CBC, а не формулы ядра.
     */
    @Test
    void solutionHitsTargetAtLaunchShiftedTick() {
        Random random = new Random(20261104L);
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            ProjectileProfile profile = new ProjectileProfile(4.0D + 6.0D * random.nextDouble(),
                    2.0D + 3.0D * random.nextDouble(), i % 2 == 0 ? 0.01D : 0.0D, 0.05D, OptionalInt.empty());
            Vector3 pivot = new Vector3(random.nextGaussian() * 50.0D, 64.0D + random.nextDouble() * 20.0D,
                    random.nextGaussian() * 50.0D);
            Vector3 position = pivot.add(new Vector3(random.nextGaussian() * 120.0D,
                    random.nextGaussian() * 15.0D, random.nextGaussian() * 120.0D));
            Vector3 velocity = new Vector3(random.nextGaussian() * 0.4D, 0.0D, random.nextGaussian() * 0.4D);
            Vector3 acceleration = new Vector3(random.nextGaussian() * 0.002D, 0.0D, random.nextGaussian() * 0.002D);
            TargetTrack.Estimate target = new TargetTrack.Estimate(position, velocity, acceleration, 10);
            AimConstraints constraints = MOUNT.withFamily(i % 3 == 0 ? ArcFamily.HIGH : ArcFamily.LOW);

            int delay = 1 + i % 2;
            Optional<FireSolution> solution = new InterceptPlanner().plan(profile, pivot, 200.0D, constraints,
                    target, delay, OptionalInt.empty());
            if (solution.isEmpty()) continue;
            FireSolution fire = solution.get();
            Vector3 aimed = target.positionAfter(delay + fire.tick());
            double miss = sweepDistance(profile, pivot, fire.direction(), fire.tick(), aimed);
            assertTrue(miss < 1.0E-6D * Math.max(1.0D, aimed.subtract(pivot).length()),
                    "case " + i + " miss " + miss + " at tick " + fire.tick());
            checked++;
        }
        assertTrue(checked > 100, "too few reachable cases: " + checked);
    }

    @Test
    void cachedHorizonFollowsProfileChanges() {
        InterceptPlanner planner = new InterceptPlanner();
        Vector3 pivot = new Vector3(0.0D, 64.0D, 0.0D);
        TargetTrack.Estimate target = new TargetTrack.Estimate(new Vector3(300.0D, 64.0D, 0.0D),
                Vector3.ZERO, Vector3.ZERO, 10);
        ProjectileProfile slow = ProjectileProfile.of(2.0D, 2.0D, 0.01D, 0.05D);
        ProjectileProfile fast = ProjectileProfile.of(8.0D, 2.0D, 0.01D, 0.05D);
        // Медленный снаряд не долетает; быстрый с тем же планировщиком обязан найти решение.
        assertTrue(planner.plan(slow, pivot, 200.0D, MOUNT, target, 1, OptionalInt.empty()).isEmpty());
        assertTrue(planner.plan(fast, pivot, 200.0D, MOUNT, target, 1, OptionalInt.empty()).isPresent());
    }

    /** Расстояние от точки до отрезка столкновений тика {@code tick} при выстреле из центра вращения. */
    private static double sweepDistance(ProjectileProfile profile, Vector3 pivot, Vector3 direction,
                                        int tick, Vector3 point) {
        Vector3 position = pivot.add(direction.scale(profile.spawnOffset()));
        Vector3 velocity = direction.scale(profile.speed());
        Vector3 gravity = new Vector3(0.0D, -profile.gravity(), 0.0D);
        for (int n = 0; ; n++) {
            Vector3 acceleration = velocity.scale(-profile.drag()).add(gravity);
            if (n == tick) {
                Vector3 segment = velocity.add(acceleration);
                double t = Math.max(0.0D, Math.min(1.0D,
                        point.subtract(position).dot(segment) / segment.dot(segment)));
                return point.subtract(position.add(segment.scale(t))).length();
            }
            position = position.add(velocity).add(acceleration.scale(0.5D));
            velocity = velocity.add(acceleration);
        }
    }
}
