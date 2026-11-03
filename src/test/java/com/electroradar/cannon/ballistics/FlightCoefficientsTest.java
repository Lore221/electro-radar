package com.electroradar.cannon.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlightCoefficientsTest {
    @ParameterizedTest
    @ValueSource(doubles = {0.0D, 1.0E-8D, 1.0E-5D, 1.0E-3D, 0.01D, 0.05D, 0.2D})
    void sweepMatchesTickRecursion(double drag) {
        ProjectileProfile profile = ProjectileProfile.of(8.0D, 2.0D, drag, 0.05D);
        FlightCoefficients flight = FlightCoefficients.of(profile);
        Vector3 direction = new Vector3(0.48D, 0.6D, -0.64D);
        CbcTickSimulator simulator = new CbcTickSimulator(profile, direction);
        for (int tick = 0; tick <= 600; tick++) {
            Sweep sweep = flight.sweep(tick);
            Vector3 start = direction.scale(sweep.radius()).add(new Vector3(0.0D, -sweep.drop(), 0.0D));
            Vector3 end = direction.scale(sweep.outerRadius()).add(new Vector3(0.0D, -sweep.drop() - sweep.fall(), 0.0D));
            double scale = Math.max(1.0D, simulator.sweepStart().length());
            assertEquals(0.0D, start.subtract(simulator.sweepStart()).length() / scale, 1.0E-12D, "start at " + tick);
            assertEquals(0.0D, end.subtract(simulator.sweepEnd()).length() / scale, 1.0E-12D, "end at " + tick);
            simulator.advance();
        }
    }
}
