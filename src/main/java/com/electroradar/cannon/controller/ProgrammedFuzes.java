package com.electroradar.cannon.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.flak.FlakAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.FragmentKillModel;
import com.electroradar.cannon.intercept.InterceptFuze;
import com.electroradar.cannon.mount.BigCannonShell;
import com.electroradar.mixin.CannonProjectileAccess;
import com.electroradar.mixin.FlakProjectileAccess;

/**
 * Программы командно-неконтактных взрывателей перехвата ({@link InterceptFuzeItem}).
 *
 * <p><b>Программирование.</b> {@code fireShot} CBC добавляет снаряд синхронно, поэтому
 * {@link EntityJoinLevelEvent} внутри {@link #fire} получает ровно этот выстрел; программа
 * хранится по снаряду, патрон не меняется. <b>Исполнение.</b> CBC вызывает взрыватель после хода
 * зенитного снаряда; цель читается из мира, а не из прогноза. Сработавший взрыватель вызывает
 * штатный подрыв CBC с упреждением {@link InterceptFuze}, а судьбу снарядов больших пушек решает
 * {@link FragmentKillModel}: осколки CBC сами снаряды не задевают. Промах подрывается после окна.</p>
 */
public final class ProgrammedFuzes {
    /** Зенитный снаряд CBC; фугасная зона 2 блока разрушает HE-снаряд, но не бронебойную болванку. */
    static final FragmentKillModel KILL_MODEL = FragmentKillModel.cbcFlak(2.0D, 2.0D);

    private static final Map<UUID, Armed> ARMED = new HashMap<>();
    private static Armed pending;

    private ProgrammedFuzes() {
    }

    /**
     * Выполняет выстрел, программируя взрыватель снаряда, который он создаст.
     *
     * @param target снаряд, против которого работает взрыватель
     */
    static boolean fire(UUID target, InterceptFuze.Program program, BooleanSupplier shot) {
        pending = new Armed(target, program);
        try {
            return shot.getAsBoolean();
        } finally {
            pending = null;
        }
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (pending == null || event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel)
                || !(event.getEntity() instanceof FlakAutocannonProjectile projectile)) {
            return;
        }
        ARMED.put(projectile.getUUID(), pending);
        pending = null;
    }

    /**
     * Ход взрывателя после движения снаряда в этом тике: отрезок — от положения в начале тика до
     * текущего.
     *
     * @return true — подорвать штатно в текущем положении (самоликвидация)
     */
    static boolean tick(AbstractCannonProjectile projectile) {
        if (ARMED.isEmpty() || !(projectile instanceof FlakAutocannonProjectile flak)
                || !(projectile.level() instanceof ServerLevel level)) {
            return false;
        }
        Armed armed = ARMED.get(projectile.getUUID());
        if (armed == null) return false;
        if (((CannonProjectileAccess) projectile).electroradar$removeNextTick()) {
            // Снаряд уже ударился в этом тике.
            ARMED.remove(projectile.getUUID());
            return false;
        }
        int flightTick = armed.flightTick++;
        if (armed.program.selfDestruct(flightTick)) {
            ARMED.remove(projectile.getUUID());
            return true;
        }
        Entity target = level.getEntity(armed.target);
        if (target == null || !target.isAlive()) return false;
        Vector3 start = new Vector3(projectile.xo, projectile.yo, projectile.zo);
        Optional<InterceptFuze.Detonation> detonation = InterceptFuze.evaluate(armed.program, flightTick, start,
                vector(projectile.position()), vector(target.getBoundingBox().getCenter()));
        if (detonation.isEmpty()) return false;
        ARMED.remove(projectile.getUUID());
        // Штатный подрыв CBC идёт из текущего положения, а этому нужна точка с упреждением.
        Vector3 point = detonation.get().point();
        ((FlakProjectileAccess) flak).electroradar$detonate(new Vec3(point.x(), point.y(), point.z()));
        ((CannonProjectileAccess) flak).electroradar$setRemoveNextTick(true);
        strike(level, vector(projectile.getDeltaMovement()), point);
        return false;
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!ARMED.isEmpty()) ARMED.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        ARMED.clear();
        pending = null;
    }

    /** Бросок поражения для каждого снаряда большой пушки в досягаемости осколков. */
    private static void strike(ServerLevel level, Vector3 flakVelocity, Vector3 point) {
        double reach = Math.max(KILL_MODEL.blastRadius(),
                KILL_MODEL.fragmentSpeedFactor() * flakVelocity.length() * KILL_MODEL.fragmentLifetime());
        AABB area = new AABB(point.x(), point.y(), point.z(), point.x(), point.y(), point.z()).inflate(reach);
        for (AbstractBigCannonProjectile candidate : level.getEntitiesOfClass(AbstractBigCannonProjectile.class, area)) {
            Optional<BigCannonShell> shell = BigCannonShell.observe(level, candidate);
            if (shell.isEmpty()) continue;
            double kill = KILL_MODEL.killProbability(flakVelocity, shell.get().shell().position().subtract(point),
                    shell.get().halfSize(), shell.get().durabilityMass());
            if (kill <= 0.0D || level.random.nextDouble() >= kill) continue;
            Vec3 at = candidate.getBoundingBox().getCenter();
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            candidate.discard();
        }
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }

    /** Программа одного снаряда и номер его следующего отрезка. */
    private static final class Armed {
        private final UUID target;
        private final InterceptFuze.Program program;
        private int flightTick;

        private Armed(UUID target, InterceptFuze.Program program) {
            this.target = target;
            this.program = program;
        }
    }
}
