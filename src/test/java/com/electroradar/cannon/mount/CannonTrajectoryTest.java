package com.electroradar.cannon.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.ArcBranch;
import com.electroradar.cannon.ballistics.ArcFamily;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.FlightHorizon;
import com.electroradar.cannon.ballistics.InterceptRequest;
import com.electroradar.cannon.ballistics.InterceptSearch;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TargetForecast;
import com.electroradar.cannon.ballistics.Vector3;

class CannonTrajectoryTest {
    @Test void autocannonIgnoresSavedHighModeAndFindsTheShortLivedLowShot() {
        var flight = FlightCoefficients.of(ProjectileProfile.of(2, 2, .01, .05).withLifetime(60));
        var horizon = FlightHorizon.compute(flight, 60, 200, 1000);
        var target = TargetForecast.stationary(new Vector3(50, 0, 0));
        FireSolution previous = null;
        for (var selected : ArcFamily.values()) {
            var constraints = new AimConstraints(-30, 60, CannonKind.AUTOCANNON.family(selected));
            var result = InterceptSearch.search(InterceptRequest.of(flight, target, constraints, horizon));
            assertEquals(ArcBranch.LOW, result.solution().orElseThrow().branch());
            if (previous != null) assertEquals(previous, result.solution().orElseThrow());
            previous = result.solution().orElseThrow();
        }
        var highOnly = AimConstraints.pitchRange(-30, 60).withFamily(ArcFamily.HIGH);
        assertFalse(InterceptSearch.search(InterceptRequest.of(flight, target, highOnly, horizon)).solution().isPresent());
        assertFalse(CannonKind.AUTOCANNON.selectableTrajectory());
        assertTrue(CannonKind.BIG_CANNON.selectableTrajectory());
    }

    @Test void mortarKeepsTheHighBranchRegardlessOfSavedMode() {
        var flight = FlightCoefficients.of(ProjectileProfile.of(6, 3, .01, .05));
        var horizon = FlightHorizon.compute(flight, 85, 200, 1200);
        for (var selected : ArcFamily.values()) {
            var constraints = new AimConstraints(15, 85, CannonKind.DROP_MORTAR.family(selected));
            var result = InterceptSearch.search(InterceptRequest.of(flight,
                    TargetForecast.stationary(new Vector3(300, 0, 0)), constraints, horizon));
            assertEquals(ArcBranch.HIGH, result.solution().orElseThrow().branch());
        }
        assertFalse(CannonKind.DROP_MORTAR.selectableTrajectory());
        assertFalse(CannonKind.DROP_MORTAR.controllerFires());
    }
}
