package com.electroradar.radar.spatial;

import java.util.*;
import com.electroradar.radar.visual.RadarVisualGeometry.Segment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Проекция только для изображения. Входы — сохранённые неизменяемые форма и поза. */
final class StructureSilhouetteProjector {
    private static final int MAX_CELLS = 262_144;
    private StructureShape baseShape;
    private Vec3 gridOrigin;
    private Set<Long> baseCells = Set.of(), unionCells;
    private List<ChildGrid> childGrids = List.of();
    private GridSilhouette.Result localProjection, drawnLocal;
    private Silhouette drawing;
    private SilhouettePlacement drawnPlacement;

    synchronized Silhouette project(StructureShape blocks, SpatialTransform root,
                                   List<StructureBody.Part> children, SilhouettePlacement placement) {
        try {
            refreshProjection(blocks, root.localOrigin(), children);
            if (drawnLocal != localProjection || !placement.equals(drawnPlacement)) {
                drawing = project(localProjection, placement);
                drawnLocal=localProjection; drawnPlacement=placement;
            }
            return drawing;
        } catch (SilhouetteBudgetExceeded exceeded) {
            return new Silhouette(List.of(), List.of(), true);
        }
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
        if (cells.size() > MAX_CELLS) throw new SilhouetteBudgetExceeded("Radar silhouette grid budget exceeded");
    }

    private static boolean samePart(StructureBody.Part first, StructureBody.Part second) {
        return first.shape() == second.shape() && first.transform().samePose(second.transform());
    }

    private static Silhouette project(GridSilhouette.Result local, SilhouettePlacement placement) {
        var fills = local.silhouette().fills().stream().map(q -> new Silhouette.Quad(placement.project(q.a()),
                placement.project(q.b()),placement.project(q.c()),placement.project(q.d()))).toList();
        var outline = local.silhouette().outline().stream().map(line -> new Segment(
                placement.project(line.start()),placement.project(line.end()))).toList();
        return new Silhouette(fills,outline,local.silhouette().detailLimited());
    }

    private record ChildGrid(StructureBody.Part part, Set<Long> cells) { }
}
