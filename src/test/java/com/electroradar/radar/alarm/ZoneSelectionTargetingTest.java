package com.electroradar.radar.alarm;

import com.electroradar.radar.spatial.SpatialTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneSelectionTargetingTest {
    private static final SpatialTransform SHIP = new SpatialTransform(new Vec3(1000000, 0, 1000000),
            new Vec3(10, 0, 20), new Vec3(0, 0, -1), new Vec3(0, 1, 0), new Vec3(1, 0, 0));

    @Test void worldHitIsProjectedIntoFirstCornersRotatedStructure() {
        assertEquals(new BlockPos(1000000, 2, 1000000), ZoneSelectionTargeting.inSelectionSpace(
                new BlockPos(10, 2, 19), SpatialTransform.IDENTITY, SHIP));
    }

    @Test void structureHitIsProjectedIntoWorldWhenFirstCornerIsOnGround() {
        assertEquals(new BlockPos(10, 2, 19), ZoneSelectionTargeting.inSelectionSpace(
                new BlockPos(1000000, 2, 1000000), SHIP, SpatialTransform.IDENTITY));
    }

    @Test void sameSpacePreservesExactBlockCoordinates() {
        BlockPos block = new BlockPos(1000003, 7, 1000005);
        assertEquals(block, ZoneSelectionTargeting.inSelectionSpace(block, SHIP, SHIP));
        assertEquals(block, ZoneSelectionTargeting.inSelectionSpace(block, SpatialTransform.IDENTITY,
                SpatialTransform.IDENTITY));
    }

    @Test void secondStructureHitUsesFirstStructuresCoordinates() {
        var other = new SpatialTransform(new Vec3(2000000, 0, 2000000), new Vec3(5, 0, 15),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        assertEquals(new BlockPos(2000005, 2, 2000004), ZoneSelectionTargeting.inSelectionSpace(
                new BlockPos(1000000, 2, 1000000), SHIP, other));
    }
}
