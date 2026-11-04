package com.electroradar.cannon.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.radar.network.TargetRef;

/** Отдельный opt-in журнал для сопоставления расчёта с реальным полётом CBC. */
public final class CannonDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("electroradar.diagnostics.cannons");
    private static final ShotAssociation<Plan> PENDING = new ShotAssociation<>();
    private static final Map<UUID, Plan> FLYING = new LinkedHashMap<>();
    private TargetRef selected;
    private boolean selectionKnown;
    private long loggedAt;
    private Object loggedStatus;

    public static boolean enabled() {
        return ENABLED;
    }

    public void selection(ServerLevel level, BlockPos controller, TargetRef target) {
        if (!ENABLED || selectionKnown && java.util.Objects.equals(selected, target)) return;
        selected = target;
        selectionKnown = true;
        loggedStatus = null;
        Entity entity = target != null && target.dimension().equals(level.dimension())
                && target.sourceKind() == TargetRef.SourceKind.ENTITY ? level.getEntity(target.id()) : null;
        LOGGER.info("[ElectroRadar/CannonDebug] event=target_selected tick={} dimension={} controller={} target={} type={} name={} position={} center={}",
                level.getGameTime(), level.dimension().location(), controller, target, type(entity),
                entity == null ? "-" : entity.getDisplayName().getString().replace('\n', ' ').replace('\r', ' '),
                entity == null ? "-" : entity.position(), entity == null ? "-" : entity.getBoundingBox().getCenter());
    }

    public void solution(ServerLevel level, Plan plan, Object status, Vec3 barrel) {
        if (!ENABLED) return;
        long now = level.getGameTime();
        if (loggedStatus == status && now >= loggedAt && now - loggedAt < 20) return;
        loggedAt = now;
        loggedStatus = status;
        ShotPrediction p = plan.prediction();
        LOGGER.info("[ElectroRadar/CannonDebug] event=solution tick={} dimension={} controller={} mount={} status={} target={} targetType={} currentTargetCenter={} pivot={} launchTarget={} launchVelocity={} acceleration={} profile={} flightTick={} fraction={} branch={} pitch={} yaw={} direction={} actualBarrel={} predictedHit={} sweepHit={} modelResidual={}",
                now, plan.dimension(), plan.controller(), plan.mount(), status, plan.target(), plan.targetType(), plan.targetCenter(),
                p.pivot(), p.target().positionAt(0).add(p.pivot()), p.target().velocity(), p.target().acceleration(),
                p.profile(), p.solution().tick(), p.solution().fraction(), p.solution().branch(),
                p.solution().pitch(), p.solution().yaw(), p.solution().direction(), barrel,
                p.impact(), p.sweepImpact(), p.impact().subtract(p.sweepImpact()).length());
    }

    public void preflight(ServerLevel level, BlockPos controller, UUID target, TrajectoryTrace.Result result) {
        if (!ENABLED) return;
        LOGGER.info("[ElectroRadar/CannonDebug] event=preflight tick={} dimension={} controller={} target={} status={} flightTick={} point={} segments={}",
                level.getGameTime(), level.dimension().location(), controller, target,
                result.status(), result.tick(), result.point(), result.segments());
    }

    public static void armed(ServerLevel level, Plan plan, boolean logAttempt) {
        if (!ENABLED || plan == null) return;
        PENDING.remember(plan.dimension() + "/" + plan.controller(), plan.dimension(), level.getGameTime(),
                plan.expectedSpawn(), plan);
        if (logAttempt) LOGGER.info("[ElectroRadar/CannonDebug] event=fire_attempt tick={} controller={} target={} expectedSpawn={} predictedHit={} expectedFlightTick={}",
                level.getGameTime(), plan.controller(), plan.target(), plan.expectedSpawn(), plan.prediction().impact(), plan.prediction().solution().tick());
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (!ENABLED || event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof AbstractCannonProjectile projectile)) return;
        var match = PENDING.match(level.dimension().location().toString(), level.getGameTime(), vector(projectile.position()));
        if (match.isEmpty()) {
            LOGGER.info("[ElectroRadar/CannonDebug] event=spawn_unmatched tick={} dimension={} projectile={} type={} position={} velocity={} association=missing_or_ambiguous",
                    level.getGameTime(), level.dimension().location(), projectile.getUUID(), type(projectile), projectile.position(), projectile.getDeltaMovement());
            return;
        }
        Plan plan = match.get();
        if (FLYING.size() >= 4096) {
            UUID oldest = FLYING.keySet().iterator().next();
            FLYING.remove(oldest);
            LOGGER.info("[ElectroRadar/CannonDebug] event=tracking_evicted projectile={} reason=capacity", oldest);
        }
        FLYING.put(projectile.getUUID(), plan);
        LOGGER.info("[ElectroRadar/CannonDebug] event=projectile_spawn tick={} dimension={} controller={} projectile={} type={} target={} position={} velocity={} actualSpeed={} expectedSpeed={} spawnError={} predictedHit={} expectedFlightTick={} fraction={} branch={} plannedDirection={} profile={} forecast={} association=unique_time_and_position",
                level.getGameTime(), plan.dimension(), plan.controller(), projectile.getUUID(), type(projectile), plan.target(),
                projectile.position(), projectile.getDeltaMovement(), projectile.getDeltaMovement().length(), plan.prediction().profile().speed(),
                vector(projectile.position()).subtract(plan.expectedSpawn()).length(), plan.prediction().impact(), plan.prediction().solution().tick(),
                plan.prediction().solution().fraction(), plan.prediction().solution().branch(), plan.prediction().solution().direction(),
                plan.prediction().profile(), plan.prediction().target());
    }

    public static void sample(AbstractCannonProjectile projectile, Vec3 start, Vec3 end) {
        if (!ENABLED || !(projectile.level() instanceof ServerLevel level)) return;
        Plan plan = FLYING.get(projectile.getUUID());
        if (plan == null) return;
        int n = Math.max(0, projectile.tickCount - 1);
        if (n != plan.prediction().solution().tick() && n % 20 != 0) return;
        ShotPrediction p = plan.prediction();
        Entity selected = level.getEntity(plan.target());
        LOGGER.info("[ElectroRadar/CannonDebug] event=flight_sample tick={} projectile={} controller={} flightTick={} expectedFlightTick={} actualStart={} actualEnd={} modelStart={} modelEnd={} startDelta={} predictedTargetNow={} selectedTargetCenter={} distanceToPredictedHit={}",
                level.getGameTime(), projectile.getUUID(), plan.controller(), n, p.solution().tick(), start, end,
                p.sweepPoint(n, 0), p.sweepPoint(n, 1), vector(start).subtract(p.sweepPoint(n, 0)), p.targetAt(n),
                selected == null ? "-" : selected.getBoundingBox().getCenter(),
                ShotPrediction.distanceToSegment(p.impact(), vector(start), vector(end)));
    }

    /** Контакт не обязательно завершает полёт: CBC может пробить блок или отскочить. */
    public static void contact(AbstractCannonProjectile projectile, Vec3 point, String kind, Object hit, Object outcome) {
        if (!ENABLED || !(projectile.level() instanceof ServerLevel level)) return;
        Plan plan = FLYING.get(projectile.getUUID());
        if (plan == null) return;
        Entity selected = level.getEntity(plan.target());
        LOGGER.info("[ElectroRadar/CannonDebug] event=contact tick={} dimension={} controller={} projectile={} flightTick={} expectedFlightTick={} kind={} hit={} point={} pointSource={} outcome={} selectedTarget={} selectedTargetCenter={} predictedHit={} impactDelta={}",
                level.getGameTime(), plan.dimension(), plan.controller(), projectile.getUUID(), Math.max(0, projectile.tickCount - 1),
                plan.prediction().solution().tick(), kind, hit, point, kind.equals("block") ? "CBC_BlockHitResult" : "expanded_entity_hitbox", outcome, plan.target(),
                selected == null ? "-" : selected.getBoundingBox().getCenter(), plan.prediction().impact(),
                vector(point).subtract(plan.prediction().impact()));
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!ENABLED || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        Plan plan = FLYING.remove(entity.getUUID());
        if (plan == null) return;
        LOGGER.info("[ElectroRadar/CannonDebug] event=projectile_removed tick={} dimension={} controller={} projectile={} age={} position={} reason={} predictedHit={}",
                level.getGameTime(), plan.dimension(), plan.controller(), entity.getUUID(), entity.tickCount,
                entity.position(), entity.getRemovalReason(), plan.prediction().impact());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        FLYING.clear();
    }

    private static String type(Entity entity) {
        return entity == null ? "unavailable" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static Vector3 vector(Vec3 value) {
        return new Vector3(value.x, value.y, value.z);
    }

    public record Plan(String dimension, BlockPos controller, BlockPos mount, UUID target, String targetType,
                       Vector3 expectedSpawn, Vector3 targetCenter, ShotPrediction prediction) {}
}
