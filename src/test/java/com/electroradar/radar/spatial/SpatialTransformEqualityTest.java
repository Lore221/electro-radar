package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpatialTransformEqualityTest {
    @Test void directPoseComparisonPreservesRecordEqualityIncludingSignedZeroAndNan() {
        for(double a:new double[]{0.0,-0.0,1,Double.NaN,Double.POSITIVE_INFINITY}) {
            for(double b:new double[]{0.0,-0.0,1,Double.NaN,Double.POSITIVE_INFINITY}) {
                var first=new SpatialTransform(new Vec3(a,0,0),new Vec3(1,a,2),new Vec3(a,0,0),
                        new Vec3(0,a,0),new Vec3(0,0,a));
                var second=new SpatialTransform(new Vec3(b,0,0),new Vec3(1,b,2),new Vec3(b,0,0),
                        new Vec3(0,b,0),new Vec3(0,0,b));
                assertEquals(first.equals(second),first.samePose(second));
            }
        }
        assertFalse(SpatialTransform.IDENTITY.samePose(null));
        assertTrue(SpatialTransform.IDENTITY.samePose(SpatialTransform.IDENTITY));
    }
}
