package com.electroradar.cannon.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.ArcBranch;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TargetForecast;
import com.electroradar.cannon.ballistics.Vector3;

class ShotPredictionTest {
    @Test void predictedHitUsesIntegerFlightTimeNotSweepFraction() {
        var profile = ProjectileProfile.of(8, 2, 0.01, 0.05);
        var flight = FlightCoefficients.of(profile);
        var direction = new Vector3(0.8, 0.6, 0);
        int n = 39;
        double f = 0.7;
        var sweep = flight.sweep(n);
        var relativeHit = direction.scale(sweep.radius() + f * sweep.step())
                .add(new Vector3(0, -sweep.drop() - f * sweep.fall(), 0));
        var velocity = new Vector3(0.2, 0.1, -0.1);
        var acceleration = new Vector3(0.001, 0, 0.002);
        var forecast = new TargetForecast(relativeHit.subtract(velocity.scale(n)).subtract(acceleration.scale(n*n/2.0)),
                velocity, acceleration);
        var pivot = new Vector3(120, 64, -80);
        var solution = new FireSolution(n, f, direction, 0, 0, ArcBranch.LOW);
        var prediction = new ShotPrediction(profile, pivot, forecast, solution);
        assertEquals(0, prediction.impact().subtract(pivot.add(relativeHit)).length(), 1e-10);
        assertEquals(0, prediction.impact().subtract(prediction.sweepImpact()).length(), 1e-10);

        // Independent CBC recurrence checks the world-coordinate origin of the logged sweep.
        var position = pivot.add(direction.scale(profile.spawnOffset()));
        var speed = direction.scale(profile.speed());
        for (int tick = 0; tick < n; tick++) {
            var force = speed.scale(-profile.drag()).add(new Vector3(0, -profile.gravity(), 0));
            position = position.add(speed).add(force.scale(0.5));
            speed = speed.add(force);
        }
        assertEquals(0, position.subtract(prediction.sweepPoint(n, 0)).length(), 1e-10);
    }

    @Test void missDistanceIsToSegmentIncludingEndpointsAndZeroLength() {
        var a = Vector3.ZERO;
        var b = new Vector3(10, 0, 0);
        assertEquals(3, ShotPrediction.distanceToSegment(new Vector3(4, 3, 0), a, b));
        assertEquals(5, ShotPrediction.distanceToSegment(new Vector3(14, 3, 0), a, b));
        assertEquals(5, ShotPrediction.distanceToSegment(new Vector3(3, 4, 0), a, a));
    }
}
