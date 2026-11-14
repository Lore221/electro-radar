package com.electroradar.radar.spatial;

import java.util.*;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.visual.RadarVisualGeometry.Segment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Кэш одной структуры: неизменяемые форма, локальная сетка и мировое размещение независимы. */
public final class StructureSnapshotCache {
    private static final int MAX_CELLS = 262_144;
    private final HorizontalCourse course = new HorizontalCourse();
    private StructureBody previous;
    private List<StructureBody.Part> previousChildren = List.of();
    private StructureShape baseShape;
    private Vec3 gridOrigin;
    private Set<Long> baseCells = Set.of(), unionCells;
    private List<ChildGrid> childGrids = List.of();
    private GridSilhouette.Result localProjection, drawnLocal, drawing;
    private Vec3 drawnOrigin, drawnForward;
    private double drawnScaleX, drawnScaleZ;

    /** Основная форма состоит из занятых единичных блоков; children заданы в пространстве root. */
    public StructureBody snapshot(TargetRef target, String name, StructureShape blocks, SpatialTransform root,
                                  List<StructureBody.Part> children, Vec3 velocity) {
        List<StructureBody.Part> parts = new ArrayList<>(children.size()+1);
        parts.add(new StructureBody.Part(blocks, root));
        for (var child : children) parts.add(new StructureBody.Part(child.shape(), root.compose(child.transform())));
        if (previous != null && target.equals(previous.target()) && name.equals(previous.measurement().name())
                && velocity.equals(previous.measurement().velocity()) && sameParts(parts, previous.parts())
                && sameParts(children, previousChildren)) return previous;

        refreshProjection(blocks, root.localOrigin(), children);
        Vec3 forward = course.resolve(root.direction(new Vec3(0,0,-1)));
        double sx = root.x().length(), sz = root.z().length();
        if (drawnLocal != localProjection || !root.worldOrigin().equals(drawnOrigin) || !forward.equals(drawnForward)
                || sx != drawnScaleX || sz != drawnScaleZ) {
            drawing = project(localProjection, root.worldOrigin(), forward, sx, sz);
            drawnLocal=localProjection; drawnOrigin=root.worldOrigin(); drawnForward=forward;
            drawnScaleX=sx; drawnScaleZ=sz;
        }
        AABB physical = null;
        for (var part : parts) {
            var bounds = part.shape().bounds(part.transform());
            if (bounds.isPresent()) physical = physical == null ? bounds.orElseThrow() : physical.minmax(bounds.orElseThrow());
        }
        if (physical == null) physical = new AABB(root.worldOrigin(), root.worldOrigin());
        Vec3 aim = physical.getCenter();
        var measurement = new StructureMeasurement(name, drawing.silhouette(), aim, velocity,
                0.5D * Math.min(physical.getXsize(), physical.getYsize()));
        previous = new StructureBody(target, parts, new Vec3(drawing.center().x,aim.y,drawing.center().z), measurement);
        previousChildren = List.copyOf(children);
        return previous;
    }

    private void refreshProjection(StructureShape blocks, Vec3 origin, List<StructureBody.Part> children) {
        boolean sameOrigin = origin.equals(gridOrigin);
        Set<Long> nextBase = baseCells;
        if (blocks != baseShape || !sameOrigin) {
            nextBase = new HashSet<>();
            for (AABB box : blocks.boxes()) nextBase.add(GridSilhouette.key((int)(box.minX-origin.x),(int)(box.minZ-origin.z)));
            checkBudget(nextBase);
            if (nextBase.equals(baseCells)) nextBase = baseCells;
        }
        boolean changed = localProjection == null || nextBase != baseCells || childGrids.size() != children.size();
        List<ChildGrid> nextChildren = new ArrayList<>(children.size());
        for (int i=0;i<children.size();i++) {
            var child = children.get(i);
            ChildGrid old = i < childGrids.size() ? childGrids.get(i) : null;
            Set<Long> cells;
            if (sameOrigin && old != null && samePart(child, old.part)) cells = old.cells;
            else {
                cells = ProjectedBlockRasterizer.rasterize(child, origin, MAX_CELLS);
                if (old != null && cells.equals(old.cells)) cells = old.cells;
            }
            changed |= old == null || cells != old.cells;
            nextChildren.add(new ChildGrid(child, cells));
        }
        if (changed) {
            Set<Long> nextUnion = new HashSet<>(nextBase);
            for (var child : nextChildren) { nextUnion.addAll(child.cells); checkBudget(nextUnion); }
            if (!nextUnion.equals(unionCells)) {
                localProjection = GridSilhouette.build(nextUnion,Vec3.ZERO,new Vec3(0,0,-1),1,1);
                unionCells = nextUnion;
            }
        }
        baseShape=blocks; gridOrigin=origin; baseCells=nextBase; childGrids=List.copyOf(nextChildren);
    }

    private static void checkBudget(Set<Long> cells) {
        if (cells.size() > MAX_CELLS) throw new IllegalStateException("Radar silhouette grid budget exceeded");
    }

    private static boolean samePart(StructureBody.Part first, StructureBody.Part second) {
        return first.shape() == second.shape() && first.transform().samePose(second.transform());
    }

    private static boolean sameParts(List<StructureBody.Part> first, List<StructureBody.Part> second) {
        if (first.size() != second.size()) return false;
        for (int i=0;i<first.size();i++) if (!samePart(first.get(i),second.get(i))) return false;
        return true;
    }

    private static GridSilhouette.Result project(GridSilhouette.Result local, Vec3 origin, Vec3 forward, double sx, double sz) {
        java.util.function.Function<Point,Point> transform = p -> new Point(
                origin.x-p.x()*sx*forward.z-p.z()*sz*forward.x,
                origin.z+p.x()*sx*forward.x-p.z()*sz*forward.z);
        var fills = local.silhouette().fills().stream().map(q -> new Silhouette.Quad(transform.apply(q.a()),
                transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()))).toList();
        var outline = local.silhouette().outline().stream().map(line -> new Segment(
                transform.apply(line.start()),transform.apply(line.end()))).toList();
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for (var q : fills) for (var p : List.of(q.a(),q.b(),q.c(),q.d())) {
            minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());
        }
        Point fallback = transform.apply(new Point(local.center().x,local.center().z));
        Vec3 center = fills.isEmpty() ? new Vec3(fallback.x(),origin.y,fallback.z())
                : new Vec3((minX+maxX)*0.5D,origin.y,(minZ+maxZ)*0.5D);
        return new GridSilhouette.Result(new Silhouette(fills,outline,local.silhouette().detailLimited()),center);
    }

    private record ChildGrid(StructureBody.Part part, Set<Long> cells) { }
}
