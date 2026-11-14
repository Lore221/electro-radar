package com.electroradar.cannon.controller;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.EngagementPlanner;
import com.electroradar.cannon.mount.MountedCannon;
import com.electroradar.radar.spatial.Placement;
import com.electroradar.radar.spatial.RadarSpatial;
import com.electroradar.radar.spatial.SpatialTransform;

/**
 * Установка CBC в своём пространстве: в мире или на движущейся структуре.
 *
 * <p>На структуре установка и её контрапция хранятся в координатах структуры, а углы и пределы
 * угла места относятся к её осям. Решатель работает в мире, где летят снаряды: центр вращения и
 * ствол переводятся позой, а мировое направление решения — обратно в углы установки. В мире
 * преобразование тождественно и всё совпадает с прежним расчётом.</p>
 */
record MountFrame(MountedCannon cannon, SpatialTransform frame) {
    private static final double PITCH_EPSILON = 1.0E-6D;

    static MountFrame of(ServerLevel level, MountedCannon cannon) {
        SpatialTransform frame = RadarSpatial.placement(level, cannon.mountPos())
                .map(Placement::transform).orElse(SpatialTransform.IDENTITY);
        return new MountFrame(cannon, frame);
    }

    Vector3 pivot() {
        return vector(frame.toWorld(cannon.pivot()));
    }

    /** Мировое направление, по которому CBC выстрелит сейчас. */
    Vec3 barrel() {
        Vec3 local = cannon.barrelDirection();
        return frame.isIdentity() ? local : frame.direction(local).normalize();
    }

    /**
     * В мире пределы угла места — мировые. На повёрнутой структуре мировой угол места не задаёт
     * местный, поэтому решатель перебирает все направления, а {@link #aimable} отсекает недоступные.
     */
    AimConstraints constraints() {
        return frame.isIdentity()
                ? new AimConstraints(Math.max(-90.0D, cannon.minPitch()), Math.min(90.0D, cannon.maxPitch()), Optional.empty())
                : new AimConstraints(-90.0D, 90.0D, Optional.empty());
    }

    boolean aimable(Vector3 world) {
        return aimable(frame, cannon.minPitch(), cannon.maxPitch(), world);
    }

    static boolean aimable(SpatialTransform frame, double minPitch, double maxPitch, Vector3 world) {
        double pitch = local(frame, world).pitch();
        return pitch >= minPitch - PITCH_EPSILON && pitch <= maxPitch + PITCH_EPSILON;
    }

    /** Текущее мировое направление ствола в углах решателя: от него оценивается разворот. */
    EngagementPlanner.Turret turret() {
        if (frame.isIdentity()) return new EngagementPlanner.Turret(cannon.yaw(), cannon.pitch());
        Angles world = angles(vector(barrel()));
        return new EngagementPlanner.Turret(world.yaw(), world.pitch());
    }

    /** Углы установки, при которых ствол смотрит по мировому направлению решения. */
    Angles local(FireSolution solution) {
        return frame.isIdentity() ? new Angles(solution.yaw(), solution.pitch()) : local(solution.direction());
    }

    private Angles local(Vector3 world) {
        return local(frame, world);
    }

    static Angles local(SpatialTransform frame, Vector3 world) {
        Vec3 local = frame.localDirection(new Vec3(world.x(), world.y(), world.z()));
        return angles(new Vector3(local.x, local.y, local.z));
    }

    /** Соглашение решателя: угол места вверх, рыскание Minecraft {@code atan2(−x, z)}. */
    private static Angles angles(Vector3 direction) {
        return new Angles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                Math.toDegrees(Math.atan2(direction.y(), Math.hypot(direction.x(), direction.z()))));
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }

    record Angles(double yaw, double pitch) { }
}
