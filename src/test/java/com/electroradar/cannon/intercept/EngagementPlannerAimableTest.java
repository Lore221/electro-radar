package com.electroradar.cannon.intercept;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.Vector3;

/** Установка на повёрнутой структуре получает только направления, доступные в её осях. */
class EngagementPlannerAimableTest {
    private static final EngagementPlanner.Policy POLICY = new EngagementPlanner.Policy(100, 1, 2, 2, 16.0D, 3, 3.0D, 3.0D);
    private static final FlightCoefficients FLAK = FlightCoefficients.of(ProjectileProfile.of(3.0D, 0.0D, 0.01D, 0.025D));
    private static final HostileShell INCOMING =
            new HostileShell(new Vector3(0, 60, 80), new Vector3(0, 0, -1.5), new LinearDragMotion(0.01D, 0.05D));
    /** Снаряд снижается высоко над установкой; встреча доступна при выстреле сейчас. */
    private static final EngagementPlanner.Target SHELL = new EngagementPlanner.Target(
            new HostileShell(INCOMING.positionAfter(1), INCOMING.velocityAfter(1), INCOMING.motion()), 200, 0.25D, 2.0D);

    @Test void solutionUsesOnlyAimableDirections() {
        assertTrue(EngagementPlanner.immediate(interceptor(direction -> true), POLICY, SHELL, OptionalInt.empty()).isPresent());
        var downOnly = EngagementPlanner.immediate(interceptor(direction -> direction.y() <= 0), POLICY, SHELL,
                OptionalInt.empty());
        assertFalse(downOnly.isPresent(), "the shell above can only be met by firing upward");
    }

    @Test void acceptedSolutionSatisfiesThePredicate() {
        var steep = EngagementPlanner.immediate(interceptor(direction -> direction.y() > 0.5), POLICY, SHELL,
                OptionalInt.empty());
        steep.ifPresent(shot -> assertTrue(shot.solution().direction().y() > 0.5));
    }

    private static EngagementPlanner.Interceptor interceptor(java.util.function.Predicate<Vector3> aimable) {
        return new EngagementPlanner.Interceptor(FLAK, new AimConstraints(-90, 90, Optional.empty()), Vector3.ZERO,
                64.0D, 1, 4, FragmentKillModel.cbcFlak(2.0D, 2.0D), aimable);
    }
}
