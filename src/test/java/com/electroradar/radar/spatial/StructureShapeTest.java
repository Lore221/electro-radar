package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.scan.*;

class StructureShapeTest {
    @Test void emptySpaceBetweenBlocksDoesNotBecomePhysicalGeometry() {
        var shape = new StructureShape(List.of(new AABB(-10,0,0,-9,1,1),new AABB(9,0,0,10,1,1)));
        var gap = new AabbRadarScanArea(new AABB(-1,0,0,1,1,1));
        assertFalse(shape.intersects(gap, SpatialTransform.IDENTITY));
        assertTrue(shape.scanPoint(gap, SpatialTransform.IDENTITY).isEmpty());
    }

    @Test void hierarchyMatchesOriginalBlockOrderForIntersectionsAndAnchors() {
        List<AABB> boxes = new ArrayList<>();
        for (int y=0;y<4;y++) for(int z=0;z<8;z++) for(int x=0;x<16;x++) {
            if ((x+z)%3!=0) boxes.add(new AABB(x,y,z,x+1,y+1,z+1));
        }
        var shape = new StructureShape(boxes);
        Random random = new Random(221);
        for(int i=0;i<1000;i++) {
            double a=random.nextDouble()*Math.PI*2;
            var frame=new SpatialTransform(Vec3.ZERO,new Vec3(100,64,100),
                    new Vec3(Math.cos(a),0,Math.sin(a)),new Vec3(0,1,0),new Vec3(-Math.sin(a),0,Math.cos(a)));
            double x=90+random.nextDouble()*40,z=90+random.nextDouble()*40;
            var area=new AabbRadarScanArea(new AABB(x,63,z,x+3,68,z+3));
            Optional<Vec3> expected=legacyScan(boxes,frame,area);
            assertEquals(expected,shape.scanPoint(area,frame));
            assertEquals(legacyIntersects(boxes,frame,area),shape.intersects(area,frame));
        }
    }

    @Test void physicalBoundsRemainExactForSparseRotatedStructures() {
        var boxes=List.of(new AABB(0,0,0,1,1,1),new AABB(10,0,10,11,1,11));
        double a=Math.PI/4;
        var frame=new SpatialTransform(Vec3.ZERO,Vec3.ZERO,new Vec3(Math.cos(a),0,Math.sin(a)),
                new Vec3(0,1,0),new Vec3(-Math.sin(a),0,Math.cos(a)));
        AABB expected=frame.bounds(boxes.getFirst()).minmax(frame.bounds(boxes.getLast()));
        assertEquals(expected,new StructureShape(boxes).bounds(frame).orElseThrow());
        assertTrue(new StructureShape(List.of()).bounds(frame).isEmpty());
    }

    @Test void farStorageWithYawPitchAndRollKeepsTheOriginalFirstAnchor() {
        Vec3 storage=new Vec3(20_000_000,0,-20_000_000);
        List<AABB> boxes=new ArrayList<>();
        for(int z=0;z<8;z++)for(int x=0;x<16;x++)boxes.add(new AABB(x,0,z,x+1,1,z+1).move(storage));
        var shape=new StructureShape(boxes);
        Random random=new Random(774);
        for(int i=0;i<200;i++) {
            double yaw=random.nextDouble()*6,pitch=random.nextDouble()*3,roll=random.nextDouble()*6;
            Vec3 x=new Vec3(Math.cos(yaw)*Math.cos(pitch),Math.sin(pitch),Math.sin(yaw)*Math.cos(pitch));
            Vec3 y0=new Vec3(-Math.cos(yaw)*Math.sin(pitch),Math.cos(pitch),-Math.sin(yaw)*Math.sin(pitch));
            Vec3 z0=x.cross(y0),y=y0.scale(Math.cos(roll)).add(z0.scale(Math.sin(roll)));
            var frame=new SpatialTransform(storage,new Vec3(20_000_000,64,-20_000_000),x,y,x.cross(y));
            Vec3 point=frame.toWorld(boxes.get(random.nextInt(boxes.size())).getCenter());
            var area=new AabbRadarScanArea(new AABB(point.x-.5,point.y-.5,point.z-.5,point.x+.5,point.y+.5,point.z+.5));
            assertEquals(legacyScan(boxes,frame,area),shape.scanPoint(area,frame));
            assertEquals(legacyIntersects(boxes,frame,area),shape.intersects(area,frame));
        }
    }

    private static boolean legacyIntersects(List<AABB> boxes,SpatialTransform frame,RadarScanArea area) {
        for(var box:boxes) if(area.queryBounds().inflate(1.0E-7).intersects(frame.bounds(box))
                && ConvexRadarIntersection.intersects(area,frame.corners(box))) return true;
        return false;
    }

    private static Optional<Vec3> legacyScan(List<AABB> boxes,SpatialTransform frame,RadarScanArea area) {
        for(var box:boxes) {
            if(!area.queryBounds().inflate(1.0E-7).intersects(frame.bounds(box)))continue;
            var corners=frame.corners(box);
            if(!ConvexRadarIntersection.intersects(area,corners))continue;
            var point=ConvexRadarIntersection.intersectionPoint(area,corners);
            if(point.isPresent())return point;
        }
        return Optional.empty();
    }
}
