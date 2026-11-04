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
        if (!(entity instanceof AbstractBigCannonProjectile projectile) || !projectile.isAlive()
                || projectile.isInGround()) {
            return Optional.empty();
        }
        Optional<BallisticPropertiesComponent> ballistics = ProjectileBallistics.of(projectile);
        if (ballistics.isEmpty() || ballistics.get().isQuadraticDrag()) return Optional.empty();
        DimensionMunitionProperties dimension = DimensionMunitionPropertiesHandler.getProperties(level);
        double gravity = Math.abs(ballistics.get().gravity()) * dimension.gravityMultiplier();
        double drag = ballistics.get().drag() * dimension.dragMultiplier();
        if (!(drag >= 0.0D && drag < 1.0D) || !Double.isFinite(gravity)) return Optional.empty();
        HostileShell shell = new HostileShell(vector(projectile.getBoundingBox().getCenter()),
                vector(projectile.getDeltaMovement()), new LinearDragMotion(drag, gravity));
        return Optional.of(new BigCannonShell(shell,
                Math.max(projectile.getBbWidth(), projectile.getBbHeight()) / 2.0D,
                Math.max(0.0D, ballistics.get().durabilityMass())));
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }
}
