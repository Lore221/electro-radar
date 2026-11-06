package com.electroradar.compat.sable;

import java.util.*;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.spatial.*;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reads real loaded blocks and nested Create geometry; caches form independently of the phase pose. */
final class SableStructureReader {
    private static final int MAX_BLOCKS = 262_144;
    private static final long MAX_CHILD_VOLUME = 1_048_576L;
    private record Cached(long period, List<AABB> boxes) { }
    private record ProjectionKey(List<AABB> blocks, List<StructureBody.Part> children) { }
    private record Projection(ProjectionKey key, GridSilhouette.Result local) { }
    private final Map<SubLevel, Projection> projections = new WeakHashMap<>();
    private final Map<SubLevel, Cached> blocks = new WeakHashMap<>();
    private final Map<KinematicContraption, Cached> children = new WeakHashMap<>();
    private final Map<SubLevel, HorizontalCourse> courses = new WeakHashMap<>();
    private final Set<SubLevel> invalid = Collections.newSetFromMap(new WeakHashMap<>());
    void invalidate(SubLevel sub) { invalid.add(sub); }
    void beginPhase() { for (SubLevel sub : invalid) blocks.remove(sub); invalid.clear(); }
    void clear() { blocks.clear(); children.clear(); courses.clear(); projections.clear(); invalid.clear(); }

    StructureBody read(ServerSubLevel sub, SpatialTransform root) {
        long period = sub.getLevel().getGameTime() / 20;
        Cached cached = blocks.get(sub);
        if (cached == null || cached.period() != period) {
            List<AABB> boxes = new ArrayList<>();
            for (var holder : sub.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                var sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    var section = sections[index];
                    if (section.hasOnlyAir()) continue;
                    int bottom = chunk.getSectionYFromSectionIndex(index) << 4;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).isAir()) {
                            boxes.add(new AABB(new BlockPos(chunk.getPos().getMinBlockX() + x, bottom + y,
                                    chunk.getPos().getMinBlockZ() + z)));
                            if (boxes.size() > MAX_BLOCKS) throw new IllegalStateException("Sable structure exceeds radar block budget");
                        }
                    }
                }
            }
            cached = new Cached(period, List.copyOf(boxes)); blocks.put(sub, cached);
        }
        List<StructureBody.Part> parts = new ArrayList<>();
        parts.add(new StructureBody.Part(cached.boxes(), root));
        List<StructureBody.Part> localParts = new ArrayList<>();
        if (sub.getPlot() instanceof ServerLevelPlot plot) for (var child : plot.getContraptions()) {
            if (!child.sable$isValid() || child.sable$getMassTracker().getCenterOfMass() == null) continue;
            Cached shape = children.get(child);
            if (shape == null || shape.period() != period) {
                var bounds = new BoundingBox3i(0, 0, 0, 0, 0, 0);
                child.sable$getLocalBounds(bounds);
                long volume = ((long)bounds.maxX() - bounds.minX() + 1) * ((long)bounds.maxY() - bounds.minY() + 1)
                        * ((long)bounds.maxZ() - bounds.minZ() + 1);
                if (volume < 0 || volume > MAX_CHILD_VOLUME) throw new IllegalStateException("Nested radar geometry budget exceeded");
                List<AABB> boxes = new ArrayList<>();
                var getter = child.sable$blockGetter();
                for (BlockPos position : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                    if (!getter.getBlockState(position).isAir()) boxes.add(new AABB(position));
                }
                shape = new Cached(period, List.copyOf(boxes)); children.put(child, shape);
            }
            var pose = child.sable$getLocalPose(new Pose3d(), 1.0D);
            var transform = SableSpatialBackend.frame(Vec3.ZERO, pose);
            localParts.add(new StructureBody.Part(shape.boxes(), transform));
            parts.add(new StructureBody.Part(shape.boxes(), root.compose(transform)));
        }
        Vec3 plotOrigin = root.localOrigin();
        ProjectionKey projectionKey = new ProjectionKey(cached.boxes(), List.copyOf(localParts));
        Projection projection = projections.get(sub);
        if (projection == null || !projection.key().equals(projectionKey)) {
            Set<Long> grid = new HashSet<>();
            for (AABB box : cached.boxes()) grid.add(GridSilhouette.key((int)(box.minX - plotOrigin.x), (int)(box.minZ - plotOrigin.z)));
            for (var child : localParts) for (AABB box : child.boxes()) {
                AABB projected = child.transform().bounds(box);
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
            projection = new Projection(projectionKey, GridSilhouette.build(grid, Vec3.ZERO, new Vec3(0, 0, -1), 1, 1));
            projections.put(sub, projection);
        }
        Vec3 forward = courses.computeIfAbsent(sub, ignored -> new HorizontalCourse())
                .resolve(root.direction(new Vec3(0, 0, -1)));
        var drawing = project(projection.local(), root.worldOrigin(), forward, root.x().length(), root.z().length());
        AABB physical = null;
        for (var part : parts) for (AABB box : part.boxes()) {
            AABB world = part.transform().bounds(box);
            physical = physical == null ? world : physical.minmax(world);
        }
        if (physical == null) physical = new AABB(root.worldOrigin(), root.worldOrigin());
        Vec3 aim = physical.getCenter();
        Vec3 velocity = new Vec3(sub.latestLinearVelocity.x, sub.latestLinearVelocity.y, sub.latestLinearVelocity.z).scale(0.05D);
        var ref = new TargetRef(sub.getLevel().dimension(), TargetRef.SourceKind.MOVING_STRUCTURE, sub.getUniqueId());
        var measurement = new StructureMeasurement(sub.getName() == null ? "" : sub.getName(), drawing.silhouette(), aim, velocity,
                0.5D * Math.min(physical.getXsize(), physical.getYsize()));
        return new StructureBody(ref, parts, new Vec3(drawing.center().x, aim.y, drawing.center().z), measurement);
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
