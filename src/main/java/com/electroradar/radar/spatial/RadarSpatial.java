package com.electroradar.radar.spatial;

import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import net.neoforged.fml.ModList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import com.electroradar.radar.network.EndpointKey;

/** The only optional-mod selection point used by ordinary radar and monitor code. */
public final class RadarSpatial {
    private static final SpatialBackend BACKEND = create();
    private RadarSpatial() { }
    private static SpatialBackend create() {
        if (!ModList.get().isLoaded("sable")) return new WorldSpatialBackend();
        // Class resolution happens only after the optional mod check.
        return new com.electroradar.compat.sable.SableSpatialBackend();
    }
    public static Vec3 worldPosition(net.minecraft.world.entity.Entity entity) {
        return placement(entity.level(), entity.blockPosition()).map(p -> p.transform().toWorld(entity.position()))
                .orElse(entity.position());
    }
    public static boolean hitboxInside(com.electroradar.radar.scan.RadarScanArea area,
                                        net.minecraft.world.entity.Entity entity) {
        var placement = placement(entity.level(), entity.blockPosition());
        if (placement.isEmpty()) return false;
        if (placement.get().endpoint() instanceof EndpointKey.Static) return area.contains(entity);
        return com.electroradar.radar.scan.ConvexRadarIntersection.intersects(area,
                placement.get().transform().corners(entity.getBoundingBox()));
    }
    public static SpatialBackend backend() { return BACKEND; }
    public static Optional<Placement> placement(Level level, BlockPos position) {
        return BACKEND.placement(level, position);
    }
    public static boolean sameSpace(Level level, BlockPos a, BlockPos b) {
        var first = placement(level, a); var second = placement(level, b);
        if (first.isEmpty() || second.isEmpty()) return false;
        var x = first.get().endpoint(); var y = second.get().endpoint();
        if (x instanceof EndpointKey.Moving moving) return y instanceof EndpointKey.Moving other
                && moving.structureId().equals(other.structureId());
        return y instanceof EndpointKey.Static;
    }
    public static Optional<BlockPos> resolve(Level level, EndpointKey endpoint) {
        return BACKEND.resolve(level, endpoint);
    }
}
