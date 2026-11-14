package com.electroradar.radar.spatial;

import java.util.*;
import java.lang.ref.WeakReference;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.network.TargetRef;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Точный физический снимок с отложенным изображением сохранённой формы и позы. */
public final class StructureSnapshotCache {
    private final HorizontalCourse course = new HorizontalCourse();
    private final StructureSilhouetteProjector projector = new StructureSilhouetteProjector();
    private StructureBody previous;
    private List<StructureBody.Part> previousChildren = List.of();
    private StructureShape drawnShape;
    private SpatialTransform drawnRoot;
    private List<StructureBody.Part> drawnChildren = List.of();
    private Vec3 drawnForward;
    private Silhouette drawing;
    private SilhouettePlacement drawnPlacement;

    /** Основная форма состоит из занятых единичных блоков; children заданы в пространстве root. */
    public StructureBody snapshot(TargetRef target, String name, StructureShape blocks, SpatialTransform root,
                                  List<StructureBody.Part> children, Vec3 velocity) {
        List<StructureBody.Part> parts = new ArrayList<>(children.size()+1);
        parts.add(new StructureBody.Part(blocks, root));
        for (var child : children) parts.add(new StructureBody.Part(child.shape(), root.compose(child.transform())));
        if (previous != null && target.equals(previous.target()) && name.equals(previous.measurement().name())
                && velocity.equals(previous.measurement().velocity()) && sameParts(parts, previous.parts())
                && sameParts(children, previousChildren)) return previous;

        Vec3 forward = course.resolve(root.direction(new Vec3(0,0,-1)));
        if (blocks != drawnShape || drawnRoot == null || !root.samePose(drawnRoot)
                || !forward.equals(drawnForward) || !sameParts(children, drawnChildren)) {
            List<StructureBody.Part> frozenChildren = List.copyOf(children);
            SilhouettePlacement placement = SilhouettePlacement.of(root, forward);
            // Старый снимок хранит только свою форму; общий кэш не продлевает жизнь новой геометрии.
            WeakReference<StructureSilhouetteProjector> capturedProjector = new WeakReference<>(projector);
            drawing = Silhouette.deferred(() -> {
                StructureSilhouetteProjector current = capturedProjector.get();
                if (current == null) current = new StructureSilhouetteProjector();
                return current.project(blocks, root, frozenChildren, placement);
            });
            drawnPlacement=placement;
            drawnShape=blocks; drawnRoot=root; drawnChildren=frozenChildren; drawnForward=forward;
        }
        AABB physical = null;
        for (var part : parts) {
            var bounds = part.shape().bounds(part.transform());
            if (bounds.isPresent()) physical = physical == null ? bounds.orElseThrow() : physical.minmax(bounds.orElseThrow());
        }
        if (physical == null) physical = new AABB(root.worldOrigin(), root.worldOrigin());
        Vec3 aim = physical.getCenter();
        var measurement = new StructureMeasurement(name, drawing, aim, velocity,
                0.5D * Math.min(physical.getXsize(), physical.getYsize()));
        previous = new StructureBody(target, parts, marker(blocks, root, children, drawnPlacement, aim), measurement);
        previousChildren = List.copyOf(children);
        return previous;
    }

    /** Приближённый экранный центр; точные физические границы считаются отдельно. */
    private static Vec3 marker(StructureShape blocks, SpatialTransform root,
                               List<StructureBody.Part> children, SilhouettePlacement placement, Vec3 aim) {
        AABB local = blocks.localBounds().orElse(null);
        for (var child : children) {
            var bounds = child.shape().localBounds();
            if (bounds.isPresent()) {
                AABB transformed = child.transform().bounds(bounds.orElseThrow());
                local = local == null ? transformed : local.minmax(transformed);
            }
        }
        if (local == null) return aim;
        Vec3 center = local.getCenter().subtract(root.localOrigin());
        Point point = placement.project(new Point(center.x, center.z));
        return new Vec3(point.x(), aim.y, point.z());
    }

    private static boolean samePart(StructureBody.Part first, StructureBody.Part second) {
        return first.shape() == second.shape() && first.transform().samePose(second.transform());
    }

    private static boolean sameParts(List<StructureBody.Part> first, List<StructureBody.Part> second) {
        if (first.size() != second.size()) return false;
        for (int i=0;i<first.size();i++) if (!samePart(first.get(i),second.get(i))) return false;
        return true;
    }

}
