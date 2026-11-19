package com.electroradar.radar.simple;

import static org.junit.jupiter.api.Assertions.*;
import com.electroradar.radar.scan.CylinderRadarScanArea;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SimpleRadarContactTest {
    @Test void circularSearchUsesRangeAndInclusiveTwentyBlockHeightRatherThanSquareCorners() {
        var area=new CylinderRadarScanArea(new Vec3(0,64,0),100,-20,20);
        assertTrue(area.intersectsBounds(new AABB(99,64,0,101,65,1)));
        assertTrue(area.intersectsBounds(new AABB(0,84,0,1,85,1)));
        assertFalse(area.intersectsBounds(new AABB(90,64,90,91,65,91)));
        assertFalse(area.intersectsBounds(new AABB(0,84.01,0,1,85,1)));
        assertThrows(IllegalArgumentException.class,()->new CylinderRadarScanArea(new Vec3(Double.NaN,0,0),100,-20,20));
    }
}
