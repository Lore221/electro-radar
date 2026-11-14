package com.electroradar.radar.spatial;

import java.util.*;
import com.electroradar.radar.network.TargetRef;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Uncached pre-optimization snapshot construction, kept only as a differential oracle. */
final class ReferenceStructureSnapshot {
    private static final int MAX_BLOCKS=262_144;
    private final HorizontalCourse course=new HorizontalCourse();
    StructureBody snapshot(TargetRef target,String name,StructureShape blocks,SpatialTransform root,
                           List<StructureBody.Part> children,Vec3 velocity) {
        List<StructureBody.Part> parts=new ArrayList<>();
        parts.add(new StructureBody.Part(blocks,root));
        for(var child:children)parts.add(new StructureBody.Part(child.shape(),root.compose(child.transform())));
        Vec3 plotOrigin=root.localOrigin();
            Set<Long> grid = new HashSet<>();
            for (AABB box : blocks.boxes()) grid.add(GridSilhouette.key((int)(box.minX - plotOrigin.x), (int)(box.minZ - plotOrigin.z)));
            for (var child : children) for (AABB box : child.boxes()) {
                AABB projected = legacyBounds(child.transform(),box);
                // Nested moving cubes are sampled on the same one-block local XZ grid.
                for (int z = (int)Math.floor(projected.minZ - plotOrigin.z); z < Math.ceil(projected.maxZ - plotOrigin.z); z++)
                    for (int x = (int)Math.floor(projected.minX - plotOrigin.x); x < Math.ceil(projected.maxX - plotOrigin.x); x++) {
                        var cell = new com.electroradar.radar.scan.AabbRadarScanArea(new AABB(
                            x + plotOrigin.x, projected.minY, z + plotOrigin.z,
                            x + plotOrigin.x + 1, projected.maxY, z + plotOrigin.z + 1));
                    if (com.electroradar.radar.scan.ConvexRadarIntersection.intersects(cell, child.transform().corners(box)))
                        grid.add(GridSilhouette.key(x, z));
                        if (grid.size() > MAX_BLOCKS) throw new IllegalStateException("Radar silhouette grid budget exceeded");
                    }
            }
        var local=GridSilhouette.build(grid,Vec3.ZERO,new Vec3(0,0,-1),1,1);
        Vec3 forward=course.resolve(root.direction(new Vec3(0,0,-1)));
        var drawing=project(local,root.worldOrigin(),forward,root.x().length(),root.z().length());
        AABB physical=null;
        for(var part:parts)for(var box:part.boxes()){
            AABB world=legacyBounds(part.transform(),box);
            physical=physical==null?world:physical.minmax(world);
        }
        if(physical==null)physical=new AABB(root.worldOrigin(),root.worldOrigin());
        Vec3 aim=physical.getCenter();
        return new StructureBody(target,parts,new Vec3(drawing.center().x,aim.y,drawing.center().z),
                new StructureMeasurement(name,drawing.silhouette(),aim,velocity,0.5D*Math.min(physical.getXsize(),physical.getYsize())));
    }
    private static AABB legacyBounds(SpatialTransform frame,AABB box){
        var corners=frame.corners(box);
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var p:corners){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);
            maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);}
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }
    private static GridSilhouette.Result project(GridSilhouette.Result local, Vec3 origin, Vec3 forward, double sx, double sz) {
        java.util.function.Function<com.electroradar.radar.visual.RadarVisualGeometry.Point,
                com.electroradar.radar.visual.RadarVisualGeometry.Point> transform = p ->
                new com.electroradar.radar.visual.RadarVisualGeometry.Point(
                    origin.x - p.x() * sx * forward.z - p.z() * sz * forward.x,
                    origin.z + p.x() * sx * forward.x - p.z() * sz * forward.z);
        var fills = local.silhouette().fills().stream().map(q -> new Silhouette.Quad(transform.apply(q.a()),
                transform.apply(q.b()), transform.apply(q.c()), transform.apply(q.d()))).toList();
        var outline = local.silhouette().outline().stream().map(line -> new com.electroradar.radar.visual.RadarVisualGeometry.Segment(
                transform.apply(line.start()), transform.apply(line.end()))).toList();
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var q : fills) for (var point : List.of(q.a(), q.b(), q.c(), q.d())) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        var fallback = transform.apply(new com.electroradar.radar.visual.RadarVisualGeometry.Point(local.center().x, local.center().z));
        Vec3 center = fills.isEmpty() ? new Vec3(fallback.x(), origin.y, fallback.z())
                : new Vec3((minX + maxX) * 0.5D, origin.y, (minZ + maxZ) * 0.5D);
        return new GridSilhouette.Result(new Silhouette(fills, outline, local.silhouette().detailLimited()), center);
    }

}
