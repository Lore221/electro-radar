package com.electroradar.cannon.controller;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.FlightHorizon;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.EngagementPlanner;
import com.electroradar.cannon.intercept.SlewModel;
import com.electroradar.cannon.mount.BigCannonShell;
import com.electroradar.cannon.mount.CannonKind;
import com.electroradar.cannon.mount.CannonMunition;
import com.electroradar.cannon.mount.MountedCannon;
import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.alarm.ShellAlarmBlockEntity;
import com.electroradar.radar.network.InterceptionNetworks;
import com.electroradar.radar.network.NetworkKind;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.TargetRef;

/**
 * Контроллер сети перехвата одного Shell Alarm. Получает угрозы только своего владельца,
 * который проверяет актуальные права радарной сети перед чтением живой цели.
 * Наводит соседнюю автопушку и программирует взрыватель при выстреле.
 */
public final class InterceptControllerBlockEntity extends SmartBlockEntity
        implements NetworkLinkTarget, IHaveGoggleInformation {
    private static final String NETWORK_ID_TAG = "electroradar_interception_network_id";
    private static final String STATUS_TAG = "electroradar_fire_status";
    private static final double MIN_AIM_TOLERANCE_DEGREES = 0.05D;
    private static final double MAX_AIM_TOLERANCE_DEGREES = 2.0D;
    /** Темп 15 автопушки CBC — перезарядка 4 тика. */
    private static final int FIRE_INTERVAL_TICKS = 4;
    private static final int SETTLE_TICKS = 3;
    /** План окна дорогой (перебор моментов пуска), поэтому пересчитывается не каждый тик. */
    private static final long WINDOW_REFRESH_TICKS = 5;
    /**
     * Правила боя: окно ищется на 5 секунд вперёд; встреча не позже 2 тиков до падения, не раньше
     * 2-го отрезка и не ближе 16 блоков от пушки; взрыватель взведён ±3 тика вокруг встречи,
     * срабатывает в 3 блоках от цели и подрывает с упреждением 3 блока.
     */
    static final EngagementPlanner.Policy POLICY = new EngagementPlanner.Policy(100, 1, 2, 2, 16.0D, 3, 3.0D, 3.0D);

    private final AimDrive drive = new AimDrive();
    private final FireTrigger trigger = new FireTrigger();
    private final TrajectoryCheckCache trajectoryCheck = new TrajectoryCheckCache();
    private final CannonMunitionCache munitionCache = new CannonMunitionCache();
    private final ShellImpactForecast impact = new ShellImpactForecast();
    private final StatusSync statusSync = new StatusSync();

    private NetworkId networkId;
    private ShellAlarmBlockEntity owner;

    private ProjectileProfile profile;
    private FlightCoefficients flight;
    private TargetRef hintTarget;
    private OptionalInt hintTick = OptionalInt.empty();
    private TargetRef windowTarget;
    private long windowAt;
    private EngagementPlanner.Engagement window;
    private Object connectedCannon;
    private final InterceptionTargetSelection targetSelection = new InterceptionTargetSelection();

    private FireStatus status = FireStatus.NO_NETWORK;

    public InterceptControllerBlockEntity(BlockPos pos, BlockState state) {
        super(InterceptControllerContent.INTERCEPT_CONTROLLER_ENTITY.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    public FireStatus status() {
        return status;
    }

    void serverTick() {
        tick();
        if (!(level instanceof ServerLevel serverLevel)) return;
        Direction facing = getBlockState().getValue(InterceptControllerBlock.FACING);
        MountedCannon cannon = MountedCannon.find(serverLevel, worldPosition.relative(facing)).orElse(null);
        Object identity = cannon == null ? null : cannon.identity();
        if (identity != connectedCannon) {
            connectedCannon = identity;
            munitionCache.invalidate();
            forgetSolution();
            drive.stop();
        }
        Outcome outcome = evaluate(serverLevel, cannon);
        targetSelection.recordOutcome(outcome.status(), serverLevel.getGameTime());
        status = outcome.status();
        boolean autocannon = cannon != null && cannon.kind() == CannonKind.AUTOCANNON;
        boolean next = autocannon && trigger.update(CannonKind.AUTOCANNON, outcome.ready(), outcome.held());
        if (!autocannon) trigger.reset();
        if (next && outcome.toFire() != null) {
            MountedCannon toFire = outcome.toFire();
            if (ProgrammedFuzes.fire(outcome.target(), outcome.shot().fuze(), () -> toFire.tryFire(serverLevel))) {
                munitionCache.invalidate();
            }
        }
        if (statusSync.update(status, serverLevel.getGameTime())) sendData();
    }

    private Outcome evaluate(ServerLevel level, MountedCannon cannon) {
        owner = InterceptionNetworks.owner(level, networkId);
        var endpoint = new EndpointKey.Static(level.dimension(), worldPosition);
        if (owner != null) InterceptionNetworks.forServer(level.getServer()).connect(endpoint, networkId);
        else InterceptionNetworks.forServer(level.getServer()).disconnect(endpoint);
        if (owner == null) return idle(FireStatus.NO_NETWORK);
        Optional<TargetRef> selected = target(level);
        if (selected.isEmpty()) return idle(FireStatus.NO_THREAT);
        TargetRef target = selected.get();

        if (cannon == null) return idle(FireStatus.NO_CANNON);
        if (cannon.kind() != CannonKind.AUTOCANNON) return idle(FireStatus.WRONG_CANNON);
        if (!cannon.controllable()) return idle(FireStatus.CANNON_BUSY);

        Optional<Entity> entity = trackedEntity(level, target);
        if (entity.isEmpty()) return idle(target.sourceKind() == TargetRef.SourceKind.ENTITY
                && target.dimension().equals(level.dimension()) ? FireStatus.TARGET_LOST : FireStatus.TARGET_UNSUPPORTED);
        Optional<BigCannonShell> shell = BigCannonShell.observe(level, entity.get());
        if (shell.isEmpty()) return idle(FireStatus.TARGET_UNSUPPORTED);

        CannonMunition reading = munition(level, cannon, false);
        if (reading.status() != CannonMunition.Status.READY) {
            return idle(reading.status() == CannonMunition.Status.NO_AMMUNITION
                    ? FireStatus.NO_AMMUNITION : FireStatus.UNSUPPORTED_MUNITION);
        }
        Optional<ItemStack> fuze = cannon.nextFlakFuze();
        if (fuze.isEmpty()) return idle(FireStatus.FLAK_REQUIRED);
        if (!(fuze.get().getItem() instanceof InterceptFuzeItem)) return idle(FireStatus.INTERCEPT_FUZE_REQUIRED);

        long now = level.getGameTime();
        Vector3 pivot = vector(cannon.pivot());
        ProjectileProfile shotProfile = reading.profile().orElseThrow();
        EngagementPlanner.Interceptor interceptor = new EngagementPlanner.Interceptor(flight(shotProfile),
                new AimConstraints(Math.max(-90.0D, cannon.minPitch()), Math.min(90.0D, cannon.maxPitch()), Optional.empty()),
                pivot, FlightHorizon.clearance(pivot.y(), level.getMinBuildHeight(), reading.reach()),
                CannonKind.AUTOCANNON.launchDelayTicks(), FIRE_INTERVAL_TICKS, ProgrammedFuzes.KILL_MODEL);
        int lastAirborne = impact.lastAirborne(target, now, shell.get().shell(), () -> new WorldTrajectoryProbe(level));
        EngagementPlanner.Target engaged = new EngagementPlanner.Target(shell.get().shell(), lastAirborne,
                shell.get().halfSize(), shell.get().durabilityMass());

        Optional<EngagementPlanner.PlannedShot> planned = EngagementPlanner.immediate(interceptor, POLICY, engaged,
                target.equals(hintTarget) ? hintTick : OptionalInt.empty());
        hintTarget = target;
        hintTick = planned.map(shot -> OptionalInt.of(shot.solution().tick())).orElse(OptionalInt.empty());
        RadarServerConfig.TargetControllerParameters parameters = RadarServerConfig.snapshot().targetController();
        double speed = AimDrive.degreesPerTick(parameters.driveRpm());
        double acceleration = AimDrive.degreesPerTickSquared(parameters.accelerationRpmPerSecond());
        if (planned.isEmpty()) {
            EngagementPlanner.Engagement engagement = window(target, now, interceptor, engaged, cannon,
                    new SlewModel(speed, acceleration, SETTLE_TICKS));
            if (engagement.status() != EngagementPlanner.Status.FEASIBLE) {
                return idle(engagement.status() == EngagementPlanner.Status.TOO_LATE
                        ? FireStatus.TOO_LATE : FireStatus.TARGET_UNREACHABLE);
            }
            // Окно впереди: доворачиваем к его первому выстрелу, огонь пока не разрешён.
            FireSolution first = engagement.shots().get(0).solution();
            AimDrive.Step step = drive.step(target, now, cannon.yaw(), cannon.pitch(), first.yaw(), first.pitch(),
                    speed, acceleration);
            cannon.aim((float) step.yaw(), (float) step.pitch());
            return new Outcome(FireStatus.WINDOW_PENDING, false, false, null, null, null);
        }

        EngagementPlanner.PlannedShot shot = planned.get();
        FireSolution fire = shot.solution();
        AimDrive.Step step = drive.step(target, now, cannon.yaw(), cannon.pitch(), fire.yaw(), fire.pitch(),
                speed, acceleration);
        cannon.aim((float) step.yaw(), (float) step.pitch());
        // Направление после поворота этого тика: по нему CBC выстрелит, по нему же проверяются допуск и путь.
        Vec3 barrel = cannon.barrelDirection();
        double range = shot.interceptPoint().subtract(pivot).length();
        // Промах ствола на дальности встречи должен остаться внутри радиуса срабатывания взрывателя.
        double tolerance = Math.max(MIN_AIM_TOLERANCE_DEGREES, Math.min(MAX_AIM_TOLERANCE_DEGREES,
                Math.toDegrees(Math.atan2(0.5D * POLICY.proximityRadius(), Math.max(range, 1.0E-6D)))));
        boolean aligned = TargetControllerBlockEntity.misalignmentDegrees(barrel, fire.direction()) <= tolerance;
        FireStatus next = aligned && step.settled() ? FireStatus.READY : FireStatus.AIMING;

        boolean canAttempt = trigger.wouldFire(CannonKind.AUTOCANNON, next == FireStatus.READY, true)
                && cannon.canFireNow();
        // Профиль перечитывается, только когда CBC готов выстрелить; при смене патрона решение пересчитывается.
        if (canAttempt && !munition(level, cannon, true).equals(reading)) {
            hintTick = OptionalInt.empty();
            return new Outcome(FireStatus.AIMING, false, false, null, null, null);
        }
        if (canAttempt) {
            Vector3 actualDirection = vector(barrel);
            var key = new TrajectoryCheckCache.Key(level, cannon.identity(), shotProfile, pivot, actualDirection,
                    fire.tick(), fire.fraction());
            Supplier<TrajectoryTrace.Result> trace = () -> TrajectoryTrace.check(shotProfile, pivot,
                    actualDirection, fire.tick(), fire.fraction(), new WorldTrajectoryProbe(level));
            TrajectoryTrace.Result checked = trajectoryCheck.check(key, now, true, trace).result();
            if (checked.status() != TrajectoryTrace.Status.CLEAR) {
                next = checked.status() == TrajectoryTrace.Status.BLOCKED
                        ? FireStatus.TRAJECTORY_BLOCKED : FireStatus.TRAJECTORY_UNLOADED;
            }
        }
        // Очередь переживает колебания привода, но не перекрытый путь.
        boolean held = next == FireStatus.READY || next == FireStatus.AIMING;
        return new Outcome(next, next == FireStatus.READY, held, canAttempt && held ? cannon : null,
                target.id(), shot);
    }

    /** Самая срочная из неотложенных угроз своего Shell Alarm. */
    private Optional<TargetRef> target(ServerLevel level) {
        UUID selected = targetSelection.select(owner.threats(), level.getGameTime());
        return selected == null ? Optional.empty()
                : Optional.of(new TargetRef(level.dimension(), TargetRef.SourceKind.ENTITY, selected));
    }

    /** План окна держится несколько тиков: первый выстрел окна задан моментом, а не тиком наблюдения. */
    private EngagementPlanner.Engagement window(TargetRef target, long now, EngagementPlanner.Interceptor interceptor,
                                                EngagementPlanner.Target engaged, MountedCannon cannon, SlewModel slew) {
        if (window == null || !target.equals(windowTarget) || now < windowAt || now - windowAt >= WINDOW_REFRESH_TICKS) {
            window = EngagementPlanner.plan(interceptor, new EngagementPlanner.Turret(cannon.yaw(), cannon.pitch()),
                    slew, POLICY, engaged);
            windowTarget = target;
            windowAt = now;
        }
        return window;
    }

    /**
     * Цель, которую сеть разрешает обстреливать: доступный радар наблюдает её или помнит в пределах
     * своего цикла. Состояние читается у живой сущности в этом измерении.
     */
    private Optional<Entity> trackedEntity(ServerLevel level, TargetRef target) {
        if (target.sourceKind() != TargetRef.SourceKind.ENTITY || !target.dimension().equals(level.dimension())) {
            return Optional.empty();
        }
        if (owner == null || !owner.permits(target)) return Optional.empty();
        Entity entity = level.getEntity(target.id());
        return entity != null && entity.isAlive() ? Optional.of(entity) : Optional.empty();
    }

    private CannonMunition munition(ServerLevel level, MountedCannon cannon, boolean fresh) {
        return munitionCache.read(cannon.identity(), cannon.spawnOffset(), level.getGameTime(), fresh,
                () -> cannon.readMunition(level));
    }

    private FlightCoefficients flight(ProjectileProfile next) {
        if (!next.equals(profile)) {
            profile = next;
            flight = FlightCoefficients.of(next);
        }
        return flight;
    }

    private Outcome idle(FireStatus reason) {
        drive.stop();
        if (reason == FireStatus.NO_THREAT || reason == FireStatus.NO_NETWORK || reason == FireStatus.TARGET_LOST
                || reason == FireStatus.TARGET_UNSUPPORTED) {
            forgetSolution();
        }
        return new Outcome(reason, false, false, null, null, null);
    }

    private void forgetSolution() {
        hintTarget = null;
        hintTick = OptionalInt.empty();
        window = null;
        windowTarget = null;
        impact.reset();
    }

    private static Vector3 vector(Vec3 vec) {
        return new Vector3(vec.x, vec.y, vec.z);
    }

    // --- Сеть ---------------------------------------------------------------------------------

    /** Привязка от предмета при установке блока. */
    void bindNetwork(NetworkId networkId) {
        closeInterceptionConnection();
        this.networkId = networkId;
        forgetSolution();
        setChanged();
    }

    @Override
    public NetworkId networkIdForLinking() {
        return networkId;
    }

    @Override
    public boolean reconfigureNetwork(NetworkId replacement) {
        if (replacement != null && (!(level instanceof ServerLevel world)
                || InterceptionNetworks.owner(world, replacement) == null)) return false;
        if (Objects.equals(networkId, replacement)) return true;
        closeInterceptionConnection();
        networkId = replacement;
        forgetSolution();
        targetSelection.clear();
        setChanged();
        return true;
    }

    @Override
    public NetworkKind networkKindForLinking() { return NetworkKind.INTERCEPTION; }

    private void closeInterceptionConnection() {
        if (level instanceof ServerLevel world) InterceptionNetworks.forServer(world.getServer())
                .disconnect(new EndpointKey.Static(world.dimension(), worldPosition));
        owner = null;
    }

    /** Create вызывает и при разрушении блока, и при выгрузке чанка; связь закрывается идемпотентно. */
    @Override
    public void invalidate() {
        closeInterceptionConnection();
        super.invalidate();
    }

    // --- Сохранение и очки --------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (networkId != null) tag.putUUID(NETWORK_ID_TAG, networkId.value());
        if (clientPacket) tag.putString(STATUS_TAG, status.name());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        NetworkId saved = tag.hasUUID(NETWORK_ID_TAG) ? new NetworkId(tag.getUUID(NETWORK_ID_TAG)) : null;
        if (!Objects.equals(saved, networkId)) {
            closeInterceptionConnection();
            networkId = saved;
        }
        if (clientPacket && tag.contains(STATUS_TAG)) {
            try {
                status = FireStatus.valueOf(tag.getString(STATUS_TAG));
            } catch (IllegalArgumentException ignored) {
                status = FireStatus.NO_NETWORK;
            }
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(
                Component.translatable("goggles.electroradar.intercept_controller")));
        tooltip.add(Component.literal("    ").append(Component.translatable(
                "goggles.electroradar.target_controller.status",
                Component.translatable(status.translationKey())
                        .withStyle(status == FireStatus.READY ? ChatFormatting.GREEN : ChatFormatting.GOLD))
                .withStyle(ChatFormatting.GRAY)));
        return true;
    }

    /**
     * Итог оценки одного тика.
     *
     * @param ready все условия выстрела выполнены
     * @param held цель по-прежнему сопровождается, хотя готовность могла пропасть на тик
     * @param toFire установка, которой разрешена попытка выстрела в этом тике
     * @param target снаряд, против которого программируется взрыватель
     * @param shot встреча и программа взрывателя выстрела этого тика
     */
    private record Outcome(FireStatus status, boolean ready, boolean held, MountedCannon toFire,
                           UUID target, EngagementPlanner.PlannedShot shot) {
    }
}
