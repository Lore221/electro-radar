package com.electroradar.cannon.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class CannonAnglesTest {
    @Test void verticalMountLimitsAreConvertedToWorldElevation() {
        var up = new CannonAngles(1, 15, 85);
        assertEquals(5, up.minWorldPitch());
        assertEquals(75, up.maxWorldPitch());
        var both = new CannonAngles(1, -30, 60);
        assertEquals(30, both.minWorldPitch());
        assertEquals(90, both.maxWorldPitch());
        var down = new CannonAngles(-1, -90, 30);
        assertEquals(-90, down.minWorldPitch());
        assertEquals(0, down.maxWorldPitch(), 1e-12);
    }

    @Test void requestedWorldDirectionMatchesCbcRotationForBothVerticalOrientations() {
        for (var mount : new CannonAngles[] {new CannonAngles(1, 15, 85), new CannonAngles(1, -85, -15),
                new CannonAngles(1, -30, 60), new CannonAngles(-1, -90, 30), new CannonAngles(-1, 15, 85)}) {
            for (double pitch = mount.minWorldPitch(); pitch <= mount.maxWorldPitch(); pitch += 5) {
                for (double yaw : new double[] {-179, -90, 0, 65, 179}) {
                    var logical = mount.logical(yaw, pitch);
                    // CBC rotates the initial Y axis around X, then around Y by -mount yaw.
                    double xRotation = Math.toRadians(mount.verticalAxis() > 0 ? -logical.pitch() : logical.pitch());
                    double localY = mount.verticalAxis() * Math.cos(xRotation);
                    double localZ = mount.verticalAxis() * Math.sin(xRotation);
                    double x = -localZ * Math.sin(Math.toRadians(logical.yaw()));
                    double z = localZ * Math.cos(Math.toRadians(logical.yaw()));
                    assertEquals(-Math.cos(Math.toRadians(pitch))*Math.sin(Math.toRadians(yaw)), x, 1e-10);
                    assertEquals(Math.sin(Math.toRadians(pitch)), localY, 1e-10);
                    assertEquals(Math.cos(Math.toRadians(pitch))*Math.cos(Math.toRadians(yaw)), z, 1e-10);
                }
            }
        }
    }
}
