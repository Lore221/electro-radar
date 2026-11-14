package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpatialTransformBoundsTest {
    @Test void affineBoundsMatchAllEightCornersIncludingFarStorageCoordinates() {
        Random random = new Random(221);
        for (int i=0;i<10_000;i++) {
            Vec3 storage = new Vec3(20_000_000, -1000, -20_000_000);
            Vec3 world = new Vec3(random.nextDouble()*40_000_000-20_000_000,
                    random.nextDouble()*1000,random.nextDouble()*40_000_000-20_000_000);
            var frame = new SpatialTransform(storage, world, vector(random), vector(random), vector(random));
            var box = new AABB(storage.add(vector(random).scale(10)), storage.add(vector(random).scale(10)));
            var corners = frame.corners(box);
            double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
            double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
            for (Vec3 corner : corners) {
                minX=Math.min(minX,corner.x);minY=Math.min(minY,corner.y);minZ=Math.min(minZ,corner.z);
                maxX=Math.max(maxX,corner.x);maxY=Math.max(maxY,corner.y);maxZ=Math.max(maxZ,corner.z);
            }
            AABB actual=frame.bounds(box);
            assertArrayEquals(new double[]{minX,minY,minZ,maxX,maxY,maxZ},
                    new double[]{actual.minX,actual.minY,actual.minZ,actual.maxX,actual.maxY,actual.maxZ},1.0E-7);
        }
    }

    @Test void zeroSizedBoxesAndIdentityArePreserved() {
        AABB point = new AABB(1,2,3,1,2,3);
        assertEquals(point, SpatialTransform.IDENTITY.bounds(point));
    }

    @Test void bulkBoundsMatchTheUnionOfEveryTransformedBlock() {
        var frame=new SpatialTransform(new Vec3(20_000_000,0,20_000_000),new Vec3(100,64,100),
                new Vec3(.8,0,.6),new Vec3(0,1,0),new Vec3(-.6,0,.8));
        var first=new AABB(20_000_000,0,20_000_000,20_000_001,1,20_000_001);
        var second=first.move(10,3,10);
        assertEquals(frame.bounds(first).minmax(frame.bounds(second)),frame.bounds(List.of(first,second)).orElseThrow());
        assertTrue(frame.bounds(List.of()).isEmpty());
    }

    private static Vec3 vector(Random random) {
        return new Vec3(random.nextDouble()*4-2,random.nextDouble()*4-2,random.nextDouble()*4-2);
    }
}
