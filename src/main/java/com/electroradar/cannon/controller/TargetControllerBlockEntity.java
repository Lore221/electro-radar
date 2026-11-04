package com.electroradar.cannon.controller;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightHorizon;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.diagnostics.CannonDiagnostics;
import com.electroradar.cannon.diagnostics.ShotPrediction;
import com.electroradar.cannon.mount.CannonKind;
import com.electroradar.cannon.mount.CannonMunition;
import com.electroradar.cannon.mount.MountedCannon;
import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.EndpointRole;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.TargetAvailability;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSources;

/**
 * Контроллер наведения: наводит соседнюю установку CBC на цель, выбранную на мониторе сети, и
 * вызывает штатный fireShot CBC, когда ствол смотрит в рассчитанную точку.
 *
 * <p><b>Цикл тика.</b> Выбор цели берётся из сети; сеть же разрешает огонь, пока доступный радар
 * наблюдает цель или помнит её. Точное движение цели читается из мира и сглаживается
 * {@link TargetTrack}. Снаряд и геометрию выстрела даёт {@link MountedCannon}, попадание ищет ядро
 * решателя через {@link InterceptPlanner}, к углам решения установку ведёт {@link AimDrive}, а
 * готовность проверяется по фактическому направлению ствола CBC, а не по ожидаемым углам.</p>
 *
 * <p>Питания нет: скорость и ускорение привода и дистанции запрета огня задаёт серверный конфиг.</p>
 */
public final class TargetControllerBlockEntity extends SmartBlockEntity
        implements NetworkLinkTarget, IHaveGoggleInformation {
    private static final String NETWORK_ID_TAG = "electroradar_network_id";
    private static final String STATUS_TAG = "electroradar_fire_status";
    private static final String CANNON_KIND_TAG = "electroradar_cannon_kind";
    /** Допуск наведения ограничен снизу точностью привода и сверху разумным разбросом очереди. */
    private static final double MIN_AIM_TOLERANCE_DEGREES = 0.05D;
    private static final double MAX_AIM_TOLERANCE_DEGREES = 2.0D;

    private final TargetTrack track = new TargetTrack();
    private final AimDrive drive = new AimDrive();
    private final FireTrigger trigger = new FireTrigger();
    private final TrajectoryCheckCache trajectoryCheck = new TrajectoryCheckCache();
    private final InterceptPlanner planner = new InterceptPlanner();
    private final CannonDiagnostics diagnostics = new CannonDiagnostics();
    private CannonDiagnostics.Plan diagnosticPlan;

    private ScrollOptionBehaviour<TrajectoryMode> trajectoryMode;
    private CannonKind connectedKind;
    private NetworkId networkId;
    private ConsumerConnection connection;
    private boolean registrationFailureLogged;

    private final CannonMunitionCache munitionCache = new CannonMunitionCache();
    private final StatusSync statusSync = new StatusSync();
    private TargetRef hintTarget;
    private OptionalInt hintTick = OptionalInt.empty();

    private FireStatus status = FireStatus.NO_NETWORK;

    public TargetControllerBlockEntity(BlockPos pos, BlockState state) {
        super(TargetControllerContent.TARGET_CONTROLLER_ENTITY.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        trajectoryMode = new ScrollOptionBehaviour<>(TrajectoryMode.class,
                Component.translatable("message.electroradar.target_controller.trajectory"), this,
                new CenteredSideValueBoxTransform(TargetControllerBlockEntity::isTrajectoryPanelFace));
        trajectoryMode.withCallback(value -> hintTick = OptionalInt.empty());
        trajectoryMode.onlyActiveWhen(() -> connectedKind != null && connectedKind.selectableTrajectory());
        behaviours.add(trajectoryMode);
    }

    /** Панель режима — на боковых гранях; лицевая занята установкой, задняя свободна для проводки. */
    private static boolean isTrajectoryPanelFace(BlockState state, Direction direction) {
        Direction facing = state.getValue(TargetControllerBlock.FACING);
        return direction == facing.getClockWise() || direction == facing.getCounterClockWise();
    }

    public FireStatus status() {
        return status;
    }

    void serverTick() {
        tick();
        if (!(level instanceof ServerLevel serverLevel)) return;
        Direction facing = getBlockState().getValue(TargetControllerBlock.FACING);
        // Одно чтение установки за тик: из него берутся и тип для панели, и орудие для наведения.
        MountedCannon cannon = MountedCannon.find(serverLevel, worldPosition.relative(facing)).orElse(null);
        CannonKind currentKind = cannon == null ? null : cannon.kind();
        if (currentKind != connectedKind) {
            connectedKind = currentKind;
            hintTick = OptionalInt.empty();
            munitionCache.invalidate();
            drive.stop();
            sendData();
        }
        Outcome outcome = evaluate(serverLevel, cannon);
        status = outcome.status();
        boolean next = outcome.kind() != null && trigger.update(outcome.kind(), outcome.ready(), outcome.held());
        if (outcome.kind() == null) trigger.reset();
        if (next && outcome.toFire() != null) {
            // Регистрация до вызова: EntityJoinLevelEvent вызывается синхронно внутри fireShot.
            CannonDiagnostics.armed(serverLevel, diagnosticPlan, true);
            if (outcome.toFire().tryFire(serverLevel)) munitionCache.invalidate();
        } else if (outcome.kind() == CannonKind.DROP_MORTAR) {
            CannonDiagnostics.armed(serverLevel, diagnosticPlan, false);
        }
        syncStatus(serverLevel.getGameTime());
    }

    private Outcome evaluate(ServerLevel level, MountedCannon cannon) {
        diagnosticPlan = null;
        ensureConnection(level);
        Optional<TargetRef> selected = connection == null ? Optional.empty() : connection.selectedTarget();
        diagnostics.selection(level, worldPosition, selected.orElse(null));
        if (connection == null) return idle(FireStatus.NO_NETWORK, null);
        if (selected.isEmpty()) return idle(FireStatus.NO_TARGET, null);
        TargetRef target = selected.get();

        if (cannon == null) return idle(FireStatus.NO_CANNON, null);
        if (!cannon.controllable()) return idle(FireStatus.CANNON_BUSY, cannon.kind());

        Optional<Entity> entity = trackedEntity(level, target);
        if (entity.isEmpty()) return idle(target.sourceKind() == TargetRef.SourceKind.ENTITY
                && target.dimension().equals(level.dimension()) ? FireStatus.TARGET_LOST
                : FireStatus.TARGET_UNSUPPORTED, cannon.kind());

        CannonMunition reading = munition(level, cannon);
        if (reading.status() != CannonMunition.Status.READY) {
            return idle(reading.status() == CannonMunition.Status.NO_AMMUNITION
                    ? FireStatus.NO_AMMUNITION : FireStatus.UNSUPPORTED_MUNITION, cannon.kind());
        }

        AABB bounds = entity.get().getBoundingBox();
        TargetTrack.Estimate estimate = track.sample(target, level.getGameTime(), vector(bounds.getCenter()),
                vector(entity.get().getDeltaMovement()), entity.get().onGround());
        Vector3 pivot = vector(cannon.pivot());
        AimConstraints constraints = new AimConstraints(Math.max(-90.0D, cannon.minPitch()),
                Math.min(90.0D, cannon.maxPitch()), cannon.kind().family(trajectoryMode.get().family()));
        Optional<FireSolution> solution = planner.plan(reading.profile().orElseThrow(), pivot,
                FlightHorizon.clearance(pivot.y(), level.getMinBuildHeight(), reading.reach()),
                constraints, estimate, cannon.kind().launchDelayTicks(),
                target.equals(hintTarget) ? hintTick : OptionalInt.empty());
        hintTarget = target;
        hintTick = solution.map(fire -> OptionalInt.of(fire.tick())).orElse(OptionalInt.empty());
        if (solution.isEmpty()) {
            drive.stop();
            return new Outcome(FireStatus.TARGET_UNREACHABLE, cannon.kind(), false, false);
        }

        FireSolution fire = solution.get();
        RadarServerConfig.TargetControllerParameters parameters = RadarServerConfig.snapshot().targetController();
        AimDrive.Step step = drive.step(target, level.getGameTime(), cannon.yaw(), cannon.pitch(),
                fire.yaw(), fire.pitch(), AimDrive.degreesPerTick(parameters.driveRpm()),
                AimDrive.degreesPerTickSquared(parameters.accelerationRpmPerSecond()));
        cannon.aim((float) step.yaw(), (float) step.pitch());
        // Направление после поворота этого тика: по нему CBC выстрелит, по нему же проверяются допуск и путь.
        Vec3 barrel = cannon.barrelDirection();

        double distance = estimate.position().subtract(pivot).length();
        double halfSize = 0.5D * Math.min(bounds.getXsize(), bounds.getYsize()) + reading.reach();
        double tolerance = Math.max(MIN_AIM_TOLERANCE_DEGREES,
                Math.min(MAX_AIM_TOLERANCE_DEGREES, Math.toDegrees(Math.atan2(halfSize, Math.max(distance, 1.0E-6D)))));
        boolean aligned = misalignmentDegrees(barrel, fire.direction()) <= tolerance;
        double minimumDistance = cannon.kind() == CannonKind.AUTOCANNON
                ? parameters.autocannonMinDistance() : parameters.bigCannonMinDistance();

        FireStatus next;
        if (distance < minimumDistance) {
            next = FireStatus.TARGET_TOO_CLOSE;
        } else if (estimate.lockTicks() < TargetTrack.ACCELERATION_WARMUP_TICKS) {
            next = FireStatus.TARGET_LOCKING;
        } else if (!aligned || !step.settled()) {
            next = FireStatus.AIMING;
        } else {
            next = FireStatus.READY;
        }
        boolean wantsFire = trigger.wouldFire(cannon.kind(), next == FireStatus.READY,
                next == FireStatus.READY || next == FireStatus.AIMING);
        boolean canAttempt = wantsFire && cannon.canFireNow();
        // Для сопровождения достаточно кеша; профиль перечитывается, только когда CBC готов выстрелить.
        // При смене боеприпаса сохраняем свежий профиль и пересчитываем решение в следующем тике.
        if (canAttempt && !munition(level, cannon, true).equals(reading)) {
            hintTick = OptionalInt.empty();
            return new Outcome(FireStatus.AIMING, cannon.kind(), false, false);
        }
        // Для миномёта это справочная проверка готовности к загрузке: сам запуск ведёт CBC.
        boolean advisory = cannon.kind() == CannonKind.DROP_MORTAR && next == FireStatus.READY;
        if (canAttempt || advisory) {
            Vector3 actualDirection = vector(barrel);
            var key = new TrajectoryCheckCache.Key(level, cannon.identity(), reading.profile().orElseThrow(),
                    pivot, actualDirection, fire.tick(), fire.fraction());
            Supplier<TrajectoryTrace.Result> trace = () -> TrajectoryTrace.check(key.profile(), pivot,
                    actualDirection, fire.tick(), fire.fraction(), new WorldTrajectoryProbe(level));
            var checked = advisory
                    ? trajectoryCheck.advisory(key, level.getGameTime(), trace)
                    : trajectoryCheck.check(key, level.getGameTime(), cannon.kind() != CannonKind.BIG_CANNON, trace);
            if (checked.result().status() != TrajectoryTrace.Status.CLEAR) {
                next = checked.result().status() == TrajectoryTrace.Status.BLOCKED
                        ? FireStatus.TRAJECTORY_BLOCKED : FireStatus.TRAJECTORY_UNLOADED;
            }
            if (!checked.reused()) diagnostics.preflight(level, worldPosition, target.id(), checked.result());
        }
        if (CannonDiagnostics.enabled()) {
            ShotPrediction prediction = new ShotPrediction(reading.profile().orElseThrow(), pivot,
                    InterceptPlanner.forecast(pivot, estimate, cannon.kind().launchDelayTicks()), fire);
            diagnosticPlan = new CannonDiagnostics.Plan(level.dimension().location().toString(),
                    worldPosition.immutable(), cannon.mountPos().immutable(), target.id(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.get().getType()).toString(),
                    pivot.add(vector(barrel).scale(cannon.spawnOffset())), estimate.position(), prediction);
            diagnostics.solution(level, diagnosticPlan, next, barrel);
        }
        // Очередь автопушки переживает колебания привода, но не запрет огня по дистанции или стороне.
        boolean held = next == FireStatus.READY || next == FireStatus.AIMING;
        return new Outcome(next, cannon.kind(), next == FireStatus.READY, held,
                canAttempt && held ? cannon : null);
    }

    /**
     * Цель, которую сеть разрешает обстреливать: доступный радар наблюдает её или помнит в пределах
     * своего цикла. Положение для наведения читается у живой сущности в этом измерении.
     */
    private Optional<Entity> trackedEntity(ServerLevel level, TargetRef target) {
        if (target.sourceKind() != TargetRef.SourceKind.ENTITY || !target.dimension().equals(level.dimension())) {
            return Optional.empty();
        }
        TargetSources sources = connection.sourcesForTarget(target);
        boolean permitted = sources.confirmed() && sources.liveResult()
                .map(LiveResult::availability).filter(TargetAvailability.AVAILABLE::equals).isPresent();
        if (!permitted) return Optional.empty();
        Entity entity = level.getEntity(target.id());
        return entity != null && entity.isAlive() ? Optional.of(entity) : Optional.empty();
    }

    private CannonMunition munition(ServerLevel level, MountedCannon cannon) {
        return munition(level, cannon, false);
    }

    private CannonMunition munition(ServerLevel level, MountedCannon cannon, boolean fresh) {
        return munitionCache.read(cannon.identity(), cannon.spawnOffset(), level.getGameTime(), fresh,
                () -> cannon.readMunition(level));
    }

    private Outcome idle(FireStatus reason, CannonKind kind) {
        drive.stop();
        if (reason == FireStatus.NO_CANNON) munitionCache.invalidate();
        if (reason == FireStatus.NO_TARGET || reason == FireStatus.NO_NETWORK) {
            track.reset();
            hintTarget = null;
            hintTick = OptionalInt.empty();
        }
        return new Outcome(reason, kind, false, false);
    }

    private void syncStatus(long now) {
        if (statusSync.update(status, now)) sendData();
    }

    /** Угол между фактическим и требуемым направлениями ствола, градусы. */
    static double misalignmentDegrees(Vec3 barrel, Vector3 wanted) {
        double cosine = barrel.x * wanted.x() + barrel.y * wanted.y() + barrel.z * wanted.z();
        double sine = new Vec3(barrel.y * wanted.z() - barrel.z * wanted.y(),
                barrel.z * wanted.x() - barrel.x * wanted.z(),
                barrel.x * wanted.y() - barrel.y * wanted.x()).length();
        return barrel.lengthSqr() < 1.0E-12D ? 180.0D : Math.toDegrees(Math.atan2(sine, cosine));
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }

    // --- Сеть ---------------------------------------------------------------------------------

    /** Привязка от предмета при установке блока. */
    void bindNetwork(NetworkId networkId) {
        reconfigureNetwork(networkId);
    }

    @Override
    public NetworkId networkIdForLinking() {
        return networkId;
    }

    @Override
    public boolean reconfigureNetwork(NetworkId replacement) {
        if (Objects.equals(networkId, replacement)) return true;
        closeConnection();
        networkId = replacement;
        track.reset();
        hintTarget = null;
        hintTick = OptionalInt.empty();
        setChanged();
        return true;
    }

    private void ensureConnection(ServerLevel level) {
        if (networkId == null) {
            closeConnection();
            return;
        }
        if (connection != null && connection.isOpen()) return;
        closeConnection();
        EndpointKey endpoint = new EndpointKey.Static(level.dimension(), worldPosition);
        try {
            connection = new ConsumerConnection(level.getServer(), endpoint, networkId, EndpointRole.CONSUMER);
            registrationFailureLogged = false;
        } catch (RuntimeException exception) {
            connection = null;
            // Следующий тик повторит регистрацию; одинаковый отказ записывается один раз.
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarDiagnostics.logException("target_controller_registration_failed", networkId, endpoint,
                        null, level.getGameTime(), null, exception);
            }
        }
    }

    private void closeConnection() {
        ConsumerConnection previous = connection;
        connection = null;
        if (previous != null) previous.close();
    }

    /** Create вызывает и при разрушении блока, и при выгрузке чанка; связь закрывается идемпотентно. */
    @Override
    public void invalidate() {
        closeConnection();
        super.invalidate();
    }

    // --- Сохранение и очки --------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (networkId != null) tag.putUUID(NETWORK_ID_TAG, networkId.value());
        if (clientPacket) {
            tag.putString(STATUS_TAG, status.name());
            if (connectedKind != null) tag.putString(CANNON_KIND_TAG, connectedKind.name());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        NetworkId saved = tag.hasUUID(NETWORK_ID_TAG) ? new NetworkId(tag.getUUID(NETWORK_ID_TAG)) : null;
        if (!Objects.equals(saved, networkId)) {
            closeConnection();
            networkId = saved;
        }
        if (clientPacket) {
            if (tag.contains(STATUS_TAG)) {
                try {
                    status = FireStatus.valueOf(tag.getString(STATUS_TAG));
                } catch (IllegalArgumentException ignored) {
                    status = FireStatus.NO_NETWORK;
                }
            }
            try {
                connectedKind = tag.contains(CANNON_KIND_TAG) ? CannonKind.valueOf(tag.getString(CANNON_KIND_TAG)) : null;
            } catch (IllegalArgumentException ignored) {
                connectedKind = null;
            }
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(
                Component.translatable("goggles.electroradar.target_controller")));
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "goggles.electroradar.target_controller.status",
                Component.translatable(status.translationKey())
                        .withStyle(status == FireStatus.READY ? ChatFormatting.GREEN : ChatFormatting.GOLD))
                .withStyle(ChatFormatting.GRAY)));
        if (trajectoryMode != null && connectedKind != null && connectedKind.selectableTrajectory()) {
            tooltip.add(Component.literal("    ").append(Component.translatable(
                    "goggles.electroradar.target_controller.trajectory",
                    Component.translatable(trajectoryMode.get().getTranslationKey()).withStyle(ChatFormatting.AQUA))
                    .withStyle(ChatFormatting.GRAY)));
        }
        return true;
    }

    /**
     * Итог оценки одного тика.
     *
     * @param kind тип найденного орудия; null — орудия нет, сигнал не подаётся
     * @param ready все условия выстрела выполнены
     * @param held огонь по цели разрешён и она достижима, хотя готовность могла пропасть на тик
     */
    private record Outcome(FireStatus status, CannonKind kind, boolean ready, boolean held, MountedCannon toFire) {
        Outcome(FireStatus status, CannonKind kind, boolean ready, boolean held) {
            this(status, kind, ready, held, null);
        }
    }
}
