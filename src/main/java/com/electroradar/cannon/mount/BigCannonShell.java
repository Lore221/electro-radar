package com.electroradar.cannon.mount;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionProperties;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;
import com.electroradar.cannon.intercept.LinearDragMotion;

/**
 * Снаряд большой пушки CBC в полёте как цель перехвата, снятый в фазе BlockEntity.
 *
 * <p>Положение — центр хитбокса: смещение от {@code position()} постоянно, поэтому закон движения
 * тот же. Скорость — {@code getDeltaMovement}: у снаряда CBC сервер задаёт её сам. Баллистика
 * читается так же, как у собственного выстрела, с множителями измерения.</p>
 *
 * @param halfSize половина наибольшего размера хитбокса
 * @param durabilityMass {@code durability_mass} снаряда: прочность против осколков
 */
public record BigCannonShell(HostileShell shell, double halfSize, double durabilityMass) {
    /** Снаряд в полёте с линейным сопротивлением; иначе модель его не описывает. */
    public static Optional<BigCannonShell> observe(ServerLevel level, Entity entity) {
        return inspect(level, entity).observation();
    }

    /** Та же проверка, с явной причиной отказа для диагностики потребителей. */
    public static Inspection inspect(ServerLevel level, Entity entity) {
        if (!(entity instanceof AbstractBigCannonProjectile projectile)) return rejected(Reason.NOT_BIG_CANNON);
        if (!projectile.isAlive()) return rejected(Reason.DEAD);
        if (projectile.isInGround()) return rejected(Reason.IN_GROUND);
        if (projectile.tickCount < 0) return rejected(Reason.INVALID_AGE);
        if (!level.hasChunkAt(projectile.blockPosition())) return rejected(Reason.UNLOADED);
        if (!level.getFluidState(projectile.blockPosition()).isEmpty()) return rejected(Reason.FLUID);
        Optional<BallisticPropertiesComponent> ballistics = ProjectileBallistics.of(projectile);
        if (ballistics.isEmpty()) return rejected(Reason.NO_BALLISTICS);
        if (ballistics.get().isQuadraticDrag()) return rejected(Reason.QUADRATIC_DRAG);
        DimensionMunitionProperties dimension = DimensionMunitionPropertiesHandler.getProperties(level);
        double gravity = projectile.isNoGravity() ? 0 : -ballistics.get().gravity() * dimension.gravityMultiplier();
        double drag = ballistics.get().drag() * dimension.dragMultiplier();
        if (!(drag >= 0.0D && drag < 1.0D)) return rejected(Reason.INVALID_DRAG);
        if (!(gravity >= 0) || !Double.isFinite(gravity)) return rejected(Reason.INVALID_GRAVITY);
        Vec3 center = projectile.getBoundingBox().getCenter();
        Vec3 velocity = projectile.getDeltaMovement();
        if (!finite(center)) return rejected(Reason.NON_FINITE_POSITION);
        if (!finite(velocity)) return rejected(Reason.NON_FINITE_VELOCITY);
        HostileShell shell = new HostileShell(vector(center), vector(velocity), new LinearDragMotion(drag, gravity));
        return new Inspection(Optional.of(new BigCannonShell(shell,
                Math.max(projectile.getBbWidth(), projectile.getBbHeight()) / 2.0D,
                Math.max(0.0D, ballistics.get().durabilityMass()))), Reason.SUPPORTED);
    }

    public enum Reason {
        SUPPORTED, NOT_BIG_CANNON, DEAD, IN_GROUND, INVALID_AGE, UNLOADED, FLUID,
        NO_BALLISTICS, QUADRATIC_DRAG, INVALID_DRAG, INVALID_GRAVITY,
        NON_FINITE_POSITION, NON_FINITE_VELOCITY
    }

    public record Inspection(Optional<BigCannonShell> observation, Reason reason) { }

    private static Inspection rejected(Reason reason) { return new Inspection(Optional.empty(), reason); }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }

    private static boolean finite(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
