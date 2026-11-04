package com.electroradar.cannon.controller;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * Геометрия блоков как в CBC: COLLIDER, без проверки жидкостей и без подгрузки чанков.
 *
 * <p>Препятствием считается любой блок с коллайдером, включая листву и стекло. Пробитие CBC
 * не гарантировано: снаряд автопушки на блоке только останавливается или рикошетит, у большой
 * пушки пробитие зависит от импульса и случайного рикошета и отключается настройкой разрушений,
 * а ударный взрыватель может сработать уже на первом блоке.</p>
 */
final class WorldTrajectoryProbe implements TrajectoryTrace.Probe {
    private final ServerLevel level;

    WorldTrajectoryProbe(ServerLevel level) { this.level = level; }

    @Override public TrajectoryTrace.Obstacle inspect(Vector3 start, Vector3 end) {
        int minX = SectionPos.posToSectionCoord(Math.min(start.x(), end.x()));
        int maxX = SectionPos.posToSectionCoord(Math.max(start.x(), end.x()));
        int minZ = SectionPos.posToSectionCoord(Math.min(start.z(), end.z()));
        int maxZ = SectionPos.posToSectionCoord(Math.max(start.z(), end.z()));
        // Проверяем всю область отрезка до level.clip: getBlockState не должен загрузить чанк.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x, z)) return new TrajectoryTrace.Obstacle(TrajectoryTrace.Status.UNLOADED, start);
            }
        }
        var hit = level.clip(new ClipContext(vec(start), vec(end), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return null;
        Vec3 point = hit.getLocation();
        return new TrajectoryTrace.Obstacle(TrajectoryTrace.Status.BLOCKED, new Vector3(point.x, point.y, point.z));
    }

    private static Vec3 vec(Vector3 v) { return new Vec3(v.x(), v.y(), v.z()); }
}
