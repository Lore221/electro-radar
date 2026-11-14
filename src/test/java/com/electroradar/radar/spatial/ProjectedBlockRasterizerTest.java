package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.electroradar.radar.scan.AabbRadarScanArea;
import com.electroradar.radar.scan.ConvexRadarIntersection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectedBlockRasterizerTest {
    @Test void preparedColumnProjectionsMatchFullThreeDimensionalSat() {
        var boxes=List.of(new AABB(0,0,0,1,1,1),new AABB(3,1,2,4,2,3));
        var shape=new StructureShape(boxes);
        Random random=new Random(221);
        for(int i=0;i<5000;i++) {
            double yaw=i<20?i*Math.PI/2:random.nextDouble()*6;
            double pitch=i<20?1.0E-10:random.nextDouble()*3,roll=random.nextDouble()*6;
            Vec3 x=new Vec3(Math.cos(yaw)*Math.cos(pitch),Math.sin(pitch),Math.sin(yaw)*Math.cos(pitch));
            Vec3 y0=new Vec3(-Math.cos(yaw)*Math.sin(pitch),Math.cos(pitch),-Math.sin(yaw)*Math.sin(pitch));
            Vec3 z0=x.cross(y0),y=y0.scale(Math.cos(roll)).add(z0.scale(Math.sin(roll)));
            Vec3 origin=i%2==0?new Vec3(20_000_000,0,-20_000_000):Vec3.ZERO;
            var frame=new SpatialTransform(Vec3.ZERO,origin.add(random.nextDouble()*4,2,random.nextDouble()*4),x,y,x.cross(y));
            var part=new StructureBody.Part(shape,frame);
            assertEquals(reference(part,origin),ProjectedBlockRasterizer.rasterize(part,origin,262144),"case "+i);
        }
    }

    @Test void axisAlignedTinyAndDegenerateBoxesKeepOriginalCellBoundariesAndBudget() {
        for(double scale:new double[]{0,1.0E-6,1,2}) {
            var frame=new SpatialTransform(Vec3.ZERO,new Vec3(2,3,4),new Vec3(scale,0,0),new Vec3(0,scale,0),new Vec3(0,0,scale));
            var part=new StructureBody.Part(List.of(new AABB(0,0,0,1,1,1)),frame);
            assertEquals(reference(part,Vec3.ZERO),ProjectedBlockRasterizer.rasterize(part,Vec3.ZERO,100));
        }
        var large=new StructureBody.Part(List.of(new AABB(0,0,0,3,1,3)),SpatialTransform.IDENTITY);
        assertThrows(IllegalStateException.class,()->ProjectedBlockRasterizer.rasterize(large,Vec3.ZERO,1));
    }

    static Set<Long> reference(StructureBody.Part part,Vec3 origin) {
        Set<Long> cells=new HashSet<>();
        for(var box:part.boxes()) {
            AABB bounds=part.transform().bounds(box);
            var corners=part.transform().corners(box);
            for(int z=(int)Math.floor(bounds.minZ-origin.z);z<Math.ceil(bounds.maxZ-origin.z);z++) {
                for(int x=(int)Math.floor(bounds.minX-origin.x);x<Math.ceil(bounds.maxX-origin.x);x++) {
                    var cell=new AabbRadarScanArea(new AABB(x+origin.x,bounds.minY,z+origin.z,
                            x+origin.x+1,bounds.maxY,z+origin.z+1));
                    if(ConvexRadarIntersection.intersects(cell,corners))cells.add(GridSilhouette.key(x,z));
                }
            }
        }
        return cells;
    }
}
