package com.electroradar.radar.alarm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.spatial.Placement;
import com.electroradar.radar.spatial.RadarSpatial;
import com.electroradar.radar.spatial.SpatialTransform;

/**
 * Пространство, в котором выбрана зона. Блоки движущейся структуры хранятся в её собственных
 * координатах, поэтому зона, выбранная на структуре, лежит в них и движется вместе с ней. Зона
 * в обычном мире неподвижна. Пространство определяется по центру зоны, как у любого блока.
 */
public final class ProtectionZoneSpace {
    private ProtectionZoneSpace() { }

    /** Серверная зона в мире на этот тик; без загруженной структуры — как обычная мировая. */
    public static ZoneVolume volume(Level level, ProtectionZone zone) {
        var placement = RadarSpatial.backend().selectionPlacement(level, center(zone));
        if (placement.isEmpty() || placement.get().endpoint() instanceof EndpointKey.Static) {
            return ZoneVolume.fixed(zone.geometry());
        }
        Vec3 velocity = RadarSpatial.backend().linearVelocity(level, placement.get().endpoint());
        return new ZoneVolume(zone.geometry(), frame(placement.get().transform()), vector(velocity));
    }

    /** Пространство зоны по её центру: мир или структура. */
    public static SpatialTransform of(Level level, ProtectionZone zone) {
        return at(level, center(zone));
    }

    /** Пространство точки: мир или структура, в чьих координатах она хранится. */
    public static SpatialTransform at(Level level, BlockPos position) {
        return RadarSpatial.backend().selectionPlacement(level, position)
                .filter(placement -> placement.endpoint() instanceof EndpointKey.Moving)
                .map(Placement::transform).orElse(SpatialTransform.IDENTITY);
    }

    /** Структура, в координатах которой хранится позиция; пусто — обычный мир. */
    public static java.util.Optional<java.util.UUID> structureOf(Level level, BlockPos position) {
        return RadarSpatial.backend().selectionPlacement(level, position)
                .filter(placement -> placement.endpoint() instanceof EndpointKey.Moving)
                .map(placement -> ((EndpointKey.Moving) placement.endpoint()).structureId());
    }

    /**
     * Точка в воздухе задаётся в мире; если первый угол лежит на структуре, она переводится в
     * координаты этой структуры, чтобы оба угла были в одном пространстве.
     */
    public static BlockPos airPoint(Level level, Vec3 world, java.util.Optional<BlockPos> firstCorner) {
        SpatialTransform space = firstCorner.map(first -> at(level, first)).orElse(SpatialTransform.IDENTITY);
        return BlockPos.containing(space.isIdentity() ? world : space.toLocal(world));
    }

    static BlockPos center(ProtectionZone zone) {
        return new BlockPos((zone.min().getX() + zone.max().getX()) >> 1, (zone.min().getY() + zone.max().getY()) >> 1,
                (zone.min().getZ() + zone.max().getZ()) >> 1);
    }

    static ZoneFrame frame(SpatialTransform transform) {
        if (transform.isIdentity()) return ZoneFrame.IDENTITY;
        return new ZoneFrame(vector(transform.localOrigin()), vector(transform.worldOrigin()),
                vector(transform.x()), vector(transform.y()), vector(transform.z()));
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }
}
