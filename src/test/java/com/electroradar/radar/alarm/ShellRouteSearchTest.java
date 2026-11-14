package com.electroradar.radar.alarm;

import java.util.Random;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;
import com.electroradar.cannon.intercept.LinearDragMotion;
import static org.junit.jupiter.api.Assertions.*;

class ShellRouteSearchTest {
    @Test void intervalSearchMatchesIndependentCbcIntegrationForMovingAndRotatedZones() {
        Random random = new Random(20261114);
        int hits = 0;
        for (int i = 0; i < 5000; i++) {
            double yaw = random.nextDouble() * Math.PI * 2, pitch = random.nextDouble() - .5;
            double c = Math.cos(yaw), s = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
            Vector3 origin = i % 2 == 0 ? Vector3.ZERO : new Vector3(1_000_000, 64, 1_000_000);
            ZoneFrame frame = new ZoneFrame(origin, new Vector3(10, 50, -20), new Vector3(c, 0, s),
                    new Vector3(-s * sp, cp, c * sp), new Vector3(-s * cp, -sp, c * cp));
            ZoneVolume volume = new ZoneVolume(new ProtectionBox(origin.subtract(new Vector3(15, 10, 15)),
                    origin.add(new Vector3(15, 10, 15))), frame,
                    new Vector3(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5));
            HostileShell shell = new HostileShell(new Vector3(random.nextDouble() * 100 - 40,
                    random.nextDouble() * 100, random.nextDouble() * 100 - 70),
                    new Vector3(random.nextDouble() * 8 - 4, random.nextDouble() * 6 - 2, random.nextDouble() * 8 - 4),
                    new LinearDragMotion(i % 4 == 0 ? 0 : i % 4 == 1 ? 1e-10 : i % 4 == 2 ? .02 : .9, .001 + random.nextDouble() * .1));
            var reference = StepwiseRouteReference.predict(shell, volume, .4, 300);
            var result = ShellRoutePrediction.predict(shell, volume, .4, 300);
            assertEquals(reference.firstDangerTick(), result.firstDangerTick(), "first case " + i);
            assertEquals(reference.lastDangerTick(), result.lastDangerTick(), "last case " + i);
            assertEquals(reference.complete(), result.complete(), "complete case " + i);
            if (result.firstDangerTick() >= 0) hits++;
        }
        assertTrue(hits > 50, "The fixture must include actual intersections");
    }
}
