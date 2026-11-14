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
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reads real loaded blocks and nested Create geometry; caches form independently of the phase pose. */
final class SableStructureReader {
    private static final int MAX_BLOCKS = 262_144;
    private static final long MAX_CHILD_VOLUME = 1_048_576L;
    private record ChildShape(long period, StructureShape shape) { }
    private final Map<SubLevel, SectionShapeCache> blockSections = new WeakHashMap<>();
    private final Map<KinematicContraption, ChildShape> children = new WeakHashMap<>();
    private final Map<SubLevel, StructureSnapshotCache> snapshots = new WeakHashMap<>();
    private final Map<SubLevel, Set<Long>> pendingChanges = new WeakHashMap<>();
    void invalidate(SubLevel sub, BlockPos position) {
        pendingChanges.computeIfAbsent(sub, ignored -> new HashSet<>()).add(
                SectionPos.asLong(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
    }
    void beginPhase() {
        for (var entry : pendingChanges.entrySet()) {
            SectionShapeCache cache = blockSections.get(entry.getKey());
            if (cache != null) for (long section : entry.getValue()) cache.invalidate(section);
        }
        pendingChanges.clear();
    }
    void clear() { blockSections.clear(); children.clear(); snapshots.clear(); pendingChanges.clear(); }

    StructureBody read(ServerSubLevel sub, SpatialTransform root) {
        long period = sub.getLevel().getGameTime() / 20;
        StructureShape main = blockSections.computeIfAbsent(sub, ignored -> new SectionShapeCache(MAX_BLOCKS))
                .refresh(period, () -> loadedSections(sub));
        List<StructureBody.Part> localParts = new ArrayList<>();
        if (sub.getPlot() instanceof ServerLevelPlot plot) for (var child : plot.getContraptions()) {
            if (!child.sable$isValid() || child.sable$getMassTracker().getCenterOfMass() == null) continue;
            ChildShape shape = children.get(child);
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
                shape = new ChildShape(period, reuseShape(shape, boxes)); children.put(child, shape);
            }
            var pose = child.sable$getLocalPose(new Pose3d(), 1.0D);
            var transform = SableSpatialBackend.frame(Vec3.ZERO, pose);
            localParts.add(new StructureBody.Part(shape.shape(), transform));
        }
        Vec3 velocity = new Vec3(sub.latestLinearVelocity.x, sub.latestLinearVelocity.y, sub.latestLinearVelocity.z).scale(0.05D);
        var ref = new TargetRef(sub.getLevel().dimension(), TargetRef.SourceKind.MOVING_STRUCTURE, sub.getUniqueId());
        return snapshots.computeIfAbsent(sub, ignored -> new StructureSnapshotCache()).snapshot(ref,
                sub.getName() == null ? "" : sub.getName(), main, root, localParts, velocity);
    }

    private static List<SectionShapeCache.Section> loadedSections(ServerSubLevel sub) {
        List<SectionShapeCache.Section> result = new ArrayList<>();
        for (var holder : sub.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            var sections = chunk.getSections();
            for (int index=0;index<sections.length;index++) {
                var section = sections[index];
                if (section.hasOnlyAir()) continue;
                int sectionY = chunk.getSectionYFromSectionIndex(index);
                int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
                long id = SectionPos.asLong(chunk.getPos().x,sectionY,chunk.getPos().z);
                result.add(new SectionShapeCache.Section(id,section,() -> readSection(section,x,sectionY << 4,z)));
            }
        }
        return result;
    }

    private static List<AABB> readSection(LevelChunkSection section, int startX, int startY, int startZ) {
        List<AABB> boxes = new ArrayList<>();
        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
            if (!section.getBlockState(x,y,z).isAir()) {
                double bx=startX+x, by=startY+y, bz=startZ+z;
                boxes.add(new AABB(bx,by,bz,bx+1,by+1,bz+1));
            }
        }
        return boxes;
    }

    private static StructureShape reuseShape(ChildShape previous, List<AABB> boxes) {
        return previous != null && previous.shape().boxes().equals(boxes) ? previous.shape() : new StructureShape(boxes);
    }
}
