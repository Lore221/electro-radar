package com.electroradar.cannon.mount;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.IAutocannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterialProperties;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.IntegratedPropellantProjectile;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionProperties;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

import com.electroradar.cannon.ballistics.ProjectileProfile;

/**
 * Поворотная установка CBC с собранным орудием — короткоживущее представление на один тик.
 *
 * <p>Геометрия выстрела повторяет {@code fireShot} CBC: центр вращения {@code C} — центр якорного
 * блока контрапции, снаряд появляется на оси ствола в {@code spawnOffset} блоков от него и
 * получает скорость вдоль той же оси. CBC берёт направление через {@code toGlobalVector(…, 0)},
 * то есть по углам прошлого тика контрапции; {@link #aim} выставляет их вместе с текущими, чтобы
 * выстрел ушёл ровно по последней команде.</p>
 *
 * <p>Объект держит ссылки на BlockEntity и сущность CBC, поэтому его нельзя сохранять между тиками.</p>
 */
public final class MountedCannon {
    /** CBC появляет снаряд большой пушки на 2 блока ближе дульного среза, автопушки — на 1,5. */
    private static final double BIG_CANNON_SPAWN_RETREAT = 2.0D;
    private static final double AUTOCANNON_SPAWN_RETREAT = 1.5D;
    /** Защита от зацикливания на повреждённой контрапции; CBC ограничивает длину ствола раньше. */
    private static final int MAX_BARREL_BLOCKS = 256;

    private final CannonMountBlockEntity mount;
    private final PitchOrientedContraptionEntity entity;
    private final AbstractMountedCannonContraption contraption;
    private final CannonKind kind;
    private final Direction orientation;
    private final Vec3 localMuzzle;
    private final double spawnRetreat;

    private MountedCannon(CannonMountBlockEntity mount, PitchOrientedContraptionEntity entity,
                          AbstractMountedCannonContraption contraption, CannonKind kind, BlockPos barrelEnd) {
        this.mount = mount;
        this.entity = entity;
        this.contraption = contraption;
        this.kind = kind;
        this.orientation = contraption.initialOrientation();
        this.localMuzzle = Vec3.atCenterOf(barrelEnd.relative(orientation));
        this.spawnRetreat = kind == CannonKind.BIG_CANNON ? BIG_CANNON_SPAWN_RETREAT : AUTOCANNON_SPAWN_RETREAT;
    }

    /**
     * Находит собранное орудие на поворотной установке. Неподвижные установки, вертикально
     * собранные стволы и миномёты не поддерживаются: их направление не задаётся углами установки.
     */
    public static Optional<MountedCannon> find(ServerLevel level, BlockPos mountPos) {
        if (!(level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity mount) || !mount.isRunning()) {
            return Optional.empty();
        }
        PitchOrientedContraptionEntity entity = mount.getContraption();
        if (entity == null || !entity.isAlive()
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption contraption)
                || contraption.getStartPos() == null
                || !contraption.initialOrientation().getAxis().isHorizontal()) {
            return Optional.empty();
        }
        CannonKind kind;
        BlockPos barrelEnd;
        if (contraption instanceof MountedBigCannonContraption bigCannon && !bigCannon.isDropMortar()) {
            kind = CannonKind.BIG_CANNON;
            barrelEnd = barrelEnd(contraption, contraption.getStartPos(), IBigCannonBlockEntity.class);
        } else if (contraption instanceof MountedAutocannonContraption) {
            kind = CannonKind.AUTOCANNON;
            barrelEnd = barrelEnd(contraption,
                    contraption.getStartPos().relative(contraption.initialOrientation()), IAutocannonBlockEntity.class);
        } else {
            return Optional.empty();
        }
        return Optional.of(new MountedCannon(mount, entity, contraption, kind, barrelEnd));
    }

    /** Первая позиция за непрерывной цепочкой блоков ствола — там CBC ищет дульный срез. */
    private static BlockPos barrelEnd(AbstractMountedCannonContraption contraption, BlockPos from, Class<?> barrelType) {
        BlockPos cursor = from;
        for (int i = 0; i < MAX_BARREL_BLOCKS && barrelType.isInstance(contraption.presentBlockEntities.get(cursor)); i++) {
            cursor = cursor.relative(contraption.initialOrientation());
        }
        return cursor;
    }

    public CannonKind kind() {
        return kind;
    }

    public BlockPos mountPos() {
        return mount.getBlockPos();
    }

    /** Центр вращения: от угла не зависит, относительно него задаётся прогноз цели. */
    public Vec3 pivot() {
        return entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0F);
    }

    /** Осевое расстояние от центра вращения до точки появления снаряда, {@code L} модели. */
    public double spawnOffset() {
        return Math.max(0.0D, entity.toGlobalVector(localMuzzle, 0.0F).distanceTo(pivot()) - spawnRetreat);
    }

    /** Направление, по которому CBC выстрелит прямо сейчас. */
    public Vec3 barrelDirection() {
        Vec3 axis = entity.toGlobalVector(localMuzzle, 0.0F).subtract(pivot());
        return axis.lengthSqr() < 1.0E-12D ? Vec3.ZERO : axis.normalize();
    }

    /** Рыскание установки в соглашении Minecraft, градусы. */
    public float yaw() {
        return mount.getYawOffset(0.0F);
    }

    /** Угол места установки, положительный вверх, градусы (без округления {@code getDisplayPitch}). */
    public float pitch() {
        return mount.getPitchOffset(0.0F);
    }

    public double minPitch() {
        return -entity.maximumDepression();
    }

    public double maxPitch() {
        return entity.maximumElevation();
    }

    /** Установку можно поворачивать: ею не управляет игрок, сидящий в орудии. */
    public boolean controllable() {
        return entity.canBeTurnedByController(mount);
    }

    /** Вход сигнала выстрела установки смотрит на соседа с этой стороны. */
    public boolean firesFrom(Direction side) {
        BlockState state = mount.getBlockState();
        return state.hasProperty(CannonMountBlock.HORIZONTAL_FACING)
                && state.getValue(CannonMountBlock.HORIZONTAL_FACING) == side;
    }

    /**
     * Сразу применяет углы к установке и контрапции, как {@code applyRotation} CBC, и выставляет
     * прошлые углы контрапции: выстрел в следующем тике уйдёт по этой команде, а не по прошлой.
     *
     * @return true, если углы изменились и клиентам отправлено обновление
     */
    public boolean aim(float yaw, float pitch) {
        if (!controllable()) return false;
        float clampedPitch = (float) Math.max(minPitch(), Math.min(maxPitch(), pitch));
        float wrappedYaw = yaw % 360.0F;
        boolean changed = Math.abs(wrappedYaw - mount.getYawOffset(0.0F)) > 1.0E-4F
                || Math.abs(clampedPitch - mount.getPitchOffset(0.0F)) > 1.0E-4F;
        mount.setYaw(wrappedYaw);
        mount.setPitch(clampedPitch);
        Direction initial = entity.getInitialOrientation();
        boolean positive = (initial.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                == (initial.getAxis() == Direction.Axis.X);
        entity.pitch = clampedPitch * (positive ? 1.0F : -1.0F);
        entity.yaw = wrappedYaw;
        entity.prevPitch = entity.pitch;
        entity.prevYaw = entity.yaw;
        if (changed) mount.notifyUpdate();
        return changed;
    }

    /** Читает снаряд следующего выстрела так же, как его соберёт {@code fireShot} CBC. */
    public CannonMunition readMunition(ServerLevel level) {
        try {
            return kind == CannonKind.BIG_CANNON ? readBigCannon(level) : readAutocannon(level);
        } catch (RuntimeException exception) {
            // Чужой снаряд или изменённая сборка не должны ронять серверный тик контроллера.
            return CannonMunition.of(CannonMunition.Status.UNSUPPORTED);
        }
    }

    private CannonMunition readBigCannon(ServerLevel level) {
        Map<BlockPos, BlockEntity> blockEntities = contraption.presentBlockEntities;
        float charges = 0.0F;
        AbstractBigCannonProjectile projectile = null;
        BlockPos cursor = contraption.getStartPos();
        for (int i = 0; i < MAX_BARREL_BLOCKS && blockEntities.get(cursor) instanceof IBigCannonBlockEntity cannon; i++) {
            StructureBlockInfo info = cannon.cannonBehavior().block();
            Block block = info.state().getBlock();
            if (block instanceof BigCannonPropellantBlock propellant && !(block instanceof ProjectileBlock<?>)) {
                if (projectile == null) charges += Math.max(0.0F, propellant.getChargePower(info));
            } else if (block instanceof ProjectileBlock<?> projectileBlock && projectile == null) {
                projectile = projectileBlock.getProjectile(level, projectileBlocks(cursor));
            }
            cursor = cursor.relative(orientation);
        }
        if (projectile == null) return CannonMunition.of(CannonMunition.Status.NO_AMMUNITION);
        charges += projectile.addedChargePower();
        if (projectile instanceof IntegratedPropellantProjectile integrated) {
            charges += Math.max(0.0F, integrated.getChargePower());
        }
        if (charges <= 0.0F || charges < projectile.minimumChargePower()) {
            return CannonMunition.of(CannonMunition.Status.NO_AMMUNITION);
        }
        // CBC поднимает слишком слабый заряд до minimumSpread материала перед выстрелом.
        float minimumSpread = contraption.getBlocks().get(BlockPos.ZERO) instanceof StructureBlockInfo anchor
                && anchor.state().getBlock() instanceof BigCannonBlock cannonBlock
                ? cannonBlock.getCannonMaterial().properties().minimumSpread() : 0.0F;
        return profile(level, projectile, Math.max(charges, minimumSpread), OptionalInt.empty());
    }

    /** Подряд идущие блоки снаряда, начиная с первого, как их передаёт CBC. */
    private List<StructureBlockInfo> projectileBlocks(BlockPos from) {
        List<StructureBlockInfo> blocks = new ArrayList<>();
        BlockPos cursor = from;
        for (int i = 0; i < MAX_BARREL_BLOCKS
                && contraption.presentBlockEntities.get(cursor) instanceof IBigCannonBlockEntity cannon; i++) {
            StructureBlockInfo info = cannon.cannonBehavior().block();
            if (!(info.state().getBlock() instanceof ProjectileBlock<?>)) break;
            blocks.add(info);
            cursor = cursor.relative(orientation);
        }
        return blocks;
    }

    private CannonMunition readAutocannon(ServerLevel level) {
        if (!(contraption.presentBlockEntities.get(contraption.getStartPos()) instanceof AbstractAutocannonBreechBlockEntity breech)
                || !(contraption.getBlocks().get(BlockPos.ZERO) instanceof StructureBlockInfo anchor)
                || !(anchor.state().getBlock() instanceof AutocannonBlock cannonBlock)) {
            return CannonMunition.of(CannonMunition.Status.NO_AMMUNITION);
        }
        ItemStack round = nextRound(breech);
        if (!(round.getItem() instanceof AutocannonAmmoItem ammo)) {
            return CannonMunition.of(CannonMunition.Status.NO_AMMUNITION);
        }
        AutocannonMaterialProperties properties = cannonBlock.getAutocannonMaterial().properties();
        int barrels = 0;
        BlockPos cursor = contraption.getStartPos().relative(orientation);
        for (int i = 0; i < MAX_BARREL_BLOCKS
                && contraption.presentBlockEntities.get(cursor) instanceof IAutocannonBlockEntity barrel
                && barrel.cannonBehavior().canLoadItem(round); i++) {
            barrels++;
            cursor = cursor.relative(orientation);
        }
        double speed = properties.baseSpeed()
                + Math.min(barrels, Math.max(0, properties.maxSpeedIncreases())) * properties.speedIncreasePerBarrel();
        AbstractAutocannonProjectile projectile = ammo.getAutocannonProjectile(round, level);
        return projectile == null ? CannonMunition.of(CannonMunition.Status.UNSUPPORTED)
                : profile(level, projectile, speed, OptionalInt.of(Math.max(1, properties.projectileLifetime())));
    }

    /** Патрон, который казённик заберёт следующим: входной буфер, затем основная и трассирующая лента. */
    private static ItemStack nextRound(AbstractAutocannonBreechBlockEntity breech) {
        ItemStack buffered = breech.getInputBuffer().peek();
        if (buffered != null && !buffered.isEmpty()) return buffered;
        ItemStack magazine = breech.getMagazine();
        if (magazine.isEmpty()) return ItemStack.EMPTY;
        ItemStack main = AutocannonAmmoContainerItem.getMainAmmoStack(magazine);
        return main.isEmpty() ? AutocannonAmmoContainerItem.getTracerAmmoStack(magazine) : main;
    }

    private CannonMunition profile(ServerLevel level, AbstractCannonProjectile projectile, double speed,
                                   OptionalInt lifetime) {
        Optional<BallisticPropertiesComponent> ballistics = ProjectileBallistics.of(projectile);
        if (ballistics.isEmpty() || ballistics.get().isQuadraticDrag() || !(speed > 0.0D)) {
            return CannonMunition.of(CannonMunition.Status.UNSUPPORTED);
        }
        DimensionMunitionProperties dimension = DimensionMunitionPropertiesHandler.getProperties(level);
        double gravity = Math.abs(ballistics.get().gravity()) * dimension.gravityMultiplier();
        double drag = ballistics.get().drag() * dimension.dragMultiplier();
        if (!(drag >= 0.0D && drag < 1.0D) || !Double.isFinite(gravity)) {
            return CannonMunition.of(CannonMunition.Status.UNSUPPORTED);
        }
        double reach = Math.max(projectile.getBbWidth(), projectile.getBbHeight()) / 2.0D;
        return CannonMunition.ready(new ProjectileProfile(speed, spawnOffset(), drag, gravity, lifetime), reach);
    }
}
