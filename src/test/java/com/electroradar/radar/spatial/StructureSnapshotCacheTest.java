package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.electroradar.radar.network.TargetRef;
import org.junit.jupiter.api.Test;

class StructureSnapshotCacheTest {
    private static final TargetRef TARGET = new TargetRef(ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld")), TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0,1));

    @Test void unchangedSnapshotsAreReusedButPoseVelocityAndNameAreFresh() {
        var cache=new StructureSnapshotCache();
        var shape=new StructureShape(List.of(new AABB(0,0,0,1,1,1)));
        var first=cache.snapshot(TARGET,"ship",shape,SpatialTransform.IDENTITY,List.of(),Vec3.ZERO);
        assertEquals(new Vec3(.5,.5,.5),first.marker());
        assertSame(first,cache.snapshot(TARGET,"ship",shape,SpatialTransform.IDENTITY,List.of(),Vec3.ZERO));
        var moved=cache.snapshot(TARGET,"renamed",shape,new SpatialTransform(Vec3.ZERO,new Vec3(10,0,0),
                SpatialTransform.IDENTITY.x(),SpatialTransform.IDENTITY.y(),SpatialTransform.IDENTITY.z()),List.of(),new Vec3(1,0,0));
        assertEquals(new Vec3(10.5,.5,.5),moved.marker());
        assertEquals("renamed",moved.measurement().name());
        assertEquals(new Vec3(1,0,0),moved.measurement().velocity());
        assertEquals(new Vec3(.5,.5,.5),first.marker(),"Earlier frozen snapshots stay immutable");
    }

    @Test void childMovementUpdatesPhysicsWhileCoveredGridKeepsSameSilhouette() {
        var cache=new StructureSnapshotCache();
        List<AABB> plate=new ArrayList<>();
        for(int z=0;z<10;z++)for(int x=0;x<10;x++)plate.add(new AABB(x,0,z,x+1,1,z+1));
        var base=new StructureShape(plate);
        var child=new StructureShape(List.of(new AABB(0,0,0,1,1,1)));
        var first=cache.snapshot(TARGET,"ship",base,SpatialTransform.IDENTITY,
                List.of(new StructureBody.Part(child,translated(2,2,2))),Vec3.ZERO);
        var next=cache.snapshot(TARGET,"ship",base,SpatialTransform.IDENTITY,
                List.of(new StructureBody.Part(child,translated(2,4,2))),Vec3.ZERO);
        assertNotEquals(first.measurement().aimCenter(),next.measurement().aimCenter());
        assertEquals(first.measurement().silhouette(),next.measurement().silhouette());
    }

    @Test void motionRotationShapeChangesAndChildAssemblyMatchUncachedSnapshots() {
        var cache=new StructureSnapshotCache();
        var reference=new ReferenceStructureSnapshot();
        Vec3 storage=new Vec3(20_000_000,0,-20_000_000);
        List<AABB> blocks=new ArrayList<>();
        for(int z=0;z<6;z++)for(int x=0;x<8;x++)if(x<2||z<2)blocks.add(new AABB(x,0,z,x+1,1,z+1).move(storage));
        var base=new StructureShape(blocks);
        var child=new StructureShape(List.of(new AABB(0,0,0,1,1,1),new AABB(1,0,0,2,1,1)));
        for(int tick=0;tick<120;tick++) {
            if(tick==40) {
                blocks.add(new AABB(2,2,2,3,3,3).move(storage));
                base=new StructureShape(blocks);
            }
            double yaw=tick*.03,pitch=tick*.02;
            var root=rotated(storage,new Vec3(100+tick*.2,64,100-tick*.3),yaw,pitch);
            List<StructureBody.Part> children=new ArrayList<>();
            if(tick<80)children.add(new StructureBody.Part(child,rotated(Vec3.ZERO,storage.add(4,2,4),tick*.07,0)));
            if(tick>20&&tick<100)children.add(new StructureBody.Part(child,translated(storage.x+2,4,storage.z+2)));
            Vec3 velocity=new Vec3(.2,0,-.3);
            String name=tick<60?"ship":"renamed";
            var expected=reference.snapshot(TARGET,name,base,root,children,velocity);
            var actual=cache.snapshot(TARGET,name,base,root,children,velocity);
            assertEquals(expected.marker().y,actual.marker().y);
            assertEquals(expected.measurement(),actual.measurement());
            assertSame(actual,cache.snapshot(TARGET,name,base,root,children,velocity));
        }
    }

    @Test void emptyMainShapeDoesNotAddItsOriginToChildPhysicalBounds() {
        var cache=new StructureSnapshotCache(); var reference=new ReferenceStructureSnapshot();
        var empty=new StructureShape(List.of());
        var children=List.of(new StructureBody.Part(List.of(new AABB(0,0,0,1,1,1)),translated(10,0,0)));
        assertEquals(reference.snapshot(TARGET,"empty",empty,SpatialTransform.IDENTITY,children,Vec3.ZERO).measurement(),
                cache.snapshot(TARGET,"empty",empty,SpatialTransform.IDENTITY,children,Vec3.ZERO).measurement());
    }

    @Test void unresolvedOldSnapshotKeepsOriginalPoseAndLargeHoleAfterNewSnapshots() {
        var cache=new StructureSnapshotCache();
        var reference=new ReferenceStructureSnapshot();
        List<AABB> ring=new ArrayList<>();
        for(int z=0;z<20;z++)for(int x=0;x<20;x++)
            if(x<3||x>=17||z<3||z>=17)ring.add(new AABB(x,0,z,x+1,1,z+1));
        var shape=new StructureShape(ring);
        var oldRoot=translated(10,64,20);
        var old=cache.snapshot(TARGET,"ship",shape,oldRoot,List.of(),Vec3.ZERO);
        assertFalse(old.measurement().silhouette().sameView(Silhouette.EMPTY));
        var current=cache.snapshot(TARGET,"changed",new StructureShape(List.of(new AABB(0,0,0,2,1,2))),
                translated(100,64,200),List.of(),Vec3.ZERO);
        current.measurement().silhouette().fills(); // Новый снимок разрешается первым.
        var expected=reference.snapshot(TARGET,"ship",shape,oldRoot,List.of(),Vec3.ZERO);
        assertEquals(expected.measurement(),old.measurement());
        assertFalse(old.measurement().silhouette().detailLimited());
        assertFalse(old.measurement().silhouette().fills().isEmpty());
        assertEquals(current.measurement(),cache.snapshot(TARGET,"changed",current.parts().getFirst().shape(),
                translated(100,64,200),List.of(),Vec3.ZERO).measurement());
    }

    @Test void approximateMarkerUsesContinuousBoundsWithoutRasterizing() {
        var cache=new StructureSnapshotCache();
        var shape=new StructureShape(List.of(new AABB(0,0,0,1,1,1)));
        var children=List.of(new StructureBody.Part(shape,translated(2.2,0,0)));
        var actual=cache.snapshot(TARGET,"ship",shape,SpatialTransform.IDENTITY,children,Vec3.ZERO);
        assertEquals(new Vec3(1.6,.5,.5),actual.marker());
        // Изменение намеренное: раньше центр отметки зависел от округлённой сетки (x=2).
        // Контур по-прежнему совпадает с растровым, а физические габариты не округляются.
        var expected=new ReferenceStructureSnapshot().snapshot(TARGET,"ship",shape,SpatialTransform.IDENTITY,children,Vec3.ZERO);
        assertEquals(expected.measurement(),actual.measurement());
        assertEquals(new Vec3(1.6,.5,.5),actual.measurement().aimCenter());
    }

    @Test void oversizedVisualGridDoesNotBreakPhysicalSnapshotOrAim() {
        var cache=new StructureSnapshotCache();
        var rootShape=new StructureShape(List.of(new AABB(0,0,0,1,1,1)));
        var child=new StructureBody.Part(List.of(new AABB(0,0,0,1,1,1)),
                new SpatialTransform(Vec3.ZERO,Vec3.ZERO,new Vec3(600,0,0),new Vec3(0,1,0),new Vec3(0,0,600)));
        var body=cache.snapshot(TARGET,"large",rootShape,SpatialTransform.IDENTITY,List.of(child),Vec3.ZERO);
        assertEquals(new Vec3(300,.5,300),body.measurement().aimCenter());
        assertEquals(2,body.parts().size());
        assertTrue(body.measurement().silhouette().detailLimited());
        assertTrue(body.measurement().silhouette().fills().isEmpty());
        assertEquals(new Vec3(300,.5,300),body.measurement().aimCenter());
        var normal=cache.snapshot(TARGET,"normal",rootShape,SpatialTransform.IDENTITY,List.of(),Vec3.ZERO);
        assertFalse(normal.measurement().silhouette().detailLimited());
        assertFalse(normal.measurement().silhouette().fills().isEmpty());
    }

    private static SpatialTransform rotated(Vec3 origin,Vec3 world,double yaw,double pitch) {
        Vec3 x=new Vec3(Math.cos(yaw)*Math.cos(pitch),Math.sin(pitch),Math.sin(yaw)*Math.cos(pitch));
        Vec3 y=new Vec3(-Math.cos(yaw)*Math.sin(pitch),Math.cos(pitch),-Math.sin(yaw)*Math.sin(pitch));
        return new SpatialTransform(origin,world,x,y,x.cross(y));
    }

    private static SpatialTransform translated(double x,double y,double z) {
        return new SpatialTransform(Vec3.ZERO,new Vec3(x,y,z),SpatialTransform.IDENTITY.x(),
                SpatialTransform.IDENTITY.y(),SpatialTransform.IDENTITY.z());
    }
}
