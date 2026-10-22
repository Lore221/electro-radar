package com.electroradar.radar.controller;

import java.util.UUID;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.RadarDiagnostics;

/**
 * Владеет состоянием одной сборки Create и её коротким визуальным наклоном. В NBT остаются UUID,
 * количество и фаза анимации; состав панелей сериализует сама contraption, не BlockEntity.
 */
final class RadarAssemblyController {
    private static final String ASSEMBLED_TAG = "Assembled";
    private static final String ENTITY_UUID_TAG = "PanelContraptionUuid";
    private static final String PANEL_COUNT_TAG = "PanelCount";
    private static final String TILT_ACTIVE_TAG = "TiltActive";
    private static final String TILT_START_TAG = "TiltStart";
    private static final String TILT_TARGET_TAG = "TiltTarget";
    private static final String TILT_DURATION_TAG = "TiltDuration";
    private static final String TILT_ELAPSED_TAG = "TiltElapsed";
    private static final int MISSING_ENTITY_CONFIRM_TICKS = 5;

    private boolean assembled;
    private UUID entityUuid;
    private int panelCount;
    private transient ControlledContraptionEntity entity;
    private transient Direction lastFacing;
    private transient int missingEntityTicks;
    private transient boolean missingEntityLogged;
    private boolean tiltActive;
    private float tiltStart;
    private float tiltTarget;
    private int tiltDuration;
    private int tiltElapsed;

    public void read(CompoundTag tag) {
        assembled = tag.getBoolean(ASSEMBLED_TAG);
        entityUuid = tag.hasUUID(ENTITY_UUID_TAG) ? tag.getUUID(ENTITY_UUID_TAG) : null;
        panelCount = Math.max(0, tag.getInt(PANEL_COUNT_TAG));
        tiltActive = tag.getBoolean(TILT_ACTIVE_TAG);
        tiltStart = tag.getFloat(TILT_START_TAG);
        tiltTarget = tag.getFloat(TILT_TARGET_TAG);
        tiltDuration = Math.clamp(tag.getInt(TILT_DURATION_TAG), 0, 20);
        tiltElapsed = Math.clamp(tag.getInt(TILT_ELAPSED_TAG), 0, Math.max(0, tiltDuration));
        if (entityUuid == null) {
            assembled = false;
            panelCount = 0;
            tiltActive = false;
        }
        entity = null;
        missingEntityTicks = 0;
        missingEntityLogged = false;
        lastFacing = null;
    }

    public void write(CompoundTag tag) {
        tag.putBoolean(ASSEMBLED_TAG, assembled);
        if (entityUuid != null) tag.putUUID(ENTITY_UUID_TAG, entityUuid);
        tag.putInt(PANEL_COUNT_TAG, panelCount);
        tag.putBoolean(TILT_ACTIVE_TAG, tiltActive);
        tag.putFloat(TILT_START_TAG, tiltStart);
        tag.putFloat(TILT_TARGET_TAG, tiltTarget);
        tag.putInt(TILT_DURATION_TAG, tiltDuration);
        tag.putInt(TILT_ELAPSED_TAG, tiltElapsed);
    }

    public void onLoad(RadarControllerBlockEntity owner) {
        entity = null;
        missingEntityTicks = 0;
        missingEntityLogged = false;
        lastFacing = owner.getBlockState().getValue(RadarControllerBlock.FACING);
    }

    public boolean isAssembled() {
        return assembled;
    }

    /** UUID учитывается и во время восстановления entity после загрузки чанка. */
    public boolean hasActiveAssembly() {
        return assembled || entityUuid != null;
    }

    public int panelCount() {
        return panelCount;
    }

    public boolean owns(ControlledContraptionEntity candidate) {
        return entityUuid != null && candidate.getUUID().equals(entityUuid);
    }

    public void tick(RadarControllerBlockEntity owner, ServerLevel level) {
        Direction facing = owner.getBlockState().getValue(RadarControllerBlock.FACING);
        if (lastFacing == null) {
            lastFacing = facing;
        } else if (lastFacing != facing) {
            // Wrench меняет только состояние блока. До расчёта следующего профиля штатно
            // возвращаем Create entity в нулевой угол, чтобы сохранённые панели не сместились.
            disassemble(owner, level, "controller_rotated");
            RadarDiagnostics.log("controller_orientation_changed", owner.networkId(), owner.endpoint(),
                    null, owner.currentTickForDiagnostics(), "from=" + lastFacing + " to=" + facing);
            lastFacing = facing;
        }

        ControlledContraptionEntity attached = resolveEntity(owner, level);
        if (assembled && attached == null) {
            // BlockEntity тикает, как только чанк получил статус block ticking, а сущности чанка
            // читаются с диска асинхронно и могут появиться заметно позже. Отсутствие entity
            // засчитывается только после того, как менеджер сущностей загрузил чанк якоря: иначе
            // медленный IO снял бы UUID, а поздно загруженная contraption осталась бы навсегда
            // непривязанной (attach принимает только сохранённый UUID) вместе со всеми панелями.
            BlockPos anchor = owner.getBlockPos().above();
            if (level.areEntitiesLoaded(ChunkPos.asLong(anchor))) missingEntityTicks++;
            if (missingEntityTicks >= MISSING_ENTITY_CONFIRM_TICKS) {
                if (!missingEntityLogged) {
                    missingEntityLogged = true;
                    RadarDiagnostics.log("contraption_lost", owner.networkId(), owner.endpoint(), null,
                            owner.currentTickForDiagnostics(), "entity=" + entityUuid
                                    + " action=deactivate_without_reassembly");
                }
                assembled = false;
                entityUuid = null;
                panelCount = 0;
                tiltActive = false;
                owner.setChanged();
            }
            return;
        }
        if (attached == null || !tiltActive) return;

        tiltElapsed = Math.min(tiltDuration, tiltElapsed + 1);
        // Вращение продолжается лишь 15 тиков, поэтому помечаем BE изменённым на каждом шаге:
        // при сохранении чанка UUID, цель и фактическая фаза остаются согласованы с Create entity.
        owner.setChanged();
        float progress = tiltDuration == 0 ? 1.0F : tiltElapsed / (float) tiltDuration;
        float current = tiltStart + (tiltTarget - tiltStart) * progress;
        attached.setAngle(current);
        if (tiltElapsed >= tiltDuration) {
            attached.setAngle(tiltTarget);
            tiltActive = false;
            owner.setChanged();
            sendTilt(attached, tiltTarget, tiltTarget, 0);
            RadarDiagnostics.log("contraption_tilt_completed", owner.networkId(), owner.endpoint(), null,
                    owner.currentTickForDiagnostics(), "entity=" + entityUuid + " angle=" + tiltTarget);
        }
    }

    public void toggleAssembly(RadarControllerBlockEntity owner, ServerLevel level, net.minecraft.world.entity.player.Player player) {
        if (assembled || entityUuid != null) {
            ControlledContraptionEntity current = resolveEntity(owner, level);
            if (current == null) {
                tell(player, RadarAssemblyFinder.Failure.ENTITY_UNAVAILABLE, 0);
                logAssembly(owner, RadarAssemblyFinder.Failure.ENTITY_UNAVAILABLE, panelCount);
                return;
            }
            boolean disassembled = disassemble(owner, level, "manual");
            if (player != null) {
                String message = disassembled ? "message.electroradar.assembly.disassembled"
                        : "message.electroradar.assembly.disassembly_failed";
                player.displayClientMessage(Component.translatable(message), true);
            }
            return;
        }

        Direction facing = owner.getBlockState().getValue(RadarControllerBlock.FACING);
        RadarServerConfig.Snapshot parameters = RadarServerConfig.snapshot();
        RadarAssemblyFinder.Discovery discovery = RadarAssemblyFinder.discover(
                level, owner.getBlockPos(), facing, parameters.maxPanels());
        if (!discovery.valid()) {
            logAssembly(owner, discovery.failure(), discovery.panelCount());
            tell(player, discovery.failure(), parameters.maxPanels());
            return;
        }
        logAssembly(owner, RadarAssemblyFinder.Failure.NONE, discovery.panelCount());

        RadarContraptionAdapter.Result result = RadarContraptionAdapter.assemble(
                level, owner, owner.mode(), discovery);
        if (!result.success()) {
            logAssembly(owner, result.failure(), discovery.panelCount());
            tell(player, result.failure(), parameters.maxPanels());
            return;
        }

        entity = result.entity();
        entityUuid = entity.getUUID();
        assembled = true;
        panelCount = discovery.panelCount();
        missingEntityTicks = 0;
        missingEntityLogged = false;
        tiltStart = 0.0F;
        tiltTarget = (float) owner.mode().panelTiltDegrees(facing);
        tiltDuration = (int) Math.ceil(Math.abs(tiltTarget - tiltStart));
        tiltElapsed = 0;
        tiltActive = tiltDuration > 0;
        owner.setChanged();
        RadarServerRuntime runtime = owner.runtime();
        runtime.invalidateProfile();
        if (tiltActive) sendTilt(entity, tiltStart, tiltTarget, tiltDuration);
        RadarDiagnostics.log("contraption_assembled", owner.networkId(), owner.endpoint(), null,
                owner.currentTickForDiagnostics(), "entity=" + entityUuid + " mode=" + owner.mode()
                        + " panels=" + panelCount + " targetAngle=" + tiltTarget);
        tell(player, RadarAssemblyFinder.Failure.NONE, panelCount);
    }

    public void prepareForBlockRemoval(RadarControllerBlockEntity owner, ServerLevel level) {
        if (entityUuid == null) return;
        ControlledContraptionEntity current = resolveEntity(owner, level);
        if (current != null) {
            disassemble(owner, level, "controller_removed");
        } else {
            RadarDiagnostics.log("contraption_unavailable_on_controller_removal", owner.networkId(),
                    owner.endpoint(), null, owner.currentTickForDiagnostics(),
                    "entity=" + entityUuid + " action=no_duplicate_panels");
            clearRuntime();
        }
    }

    private boolean disassemble(RadarControllerBlockEntity owner, ServerLevel level, String reason) {
        ControlledContraptionEntity current = resolveEntity(owner, level);
        if (current == null) return false;
        try {
            current.setAngle(0.0F);
            if (current.getContraption() instanceof RadarPanelContraption panels) {
                current.disassemble();
                panels.clearPanelProfiles(level, owner.getBlockPos());
            } else {
                current.disassemble();
                RadarDiagnostics.log("contraption_type_mismatch_on_disassemble", owner.networkId(),
                        owner.endpoint(), null, owner.currentTickForDiagnostics(), "entity=" + entityUuid);
            }
            RadarDiagnostics.log("contraption_disassembled", owner.networkId(), owner.endpoint(), null,
                    owner.currentTickForDiagnostics(), "entity=" + entityUuid + " panels=" + panelCount
                            + " reason=" + reason);
            clearRuntime();
            owner.runtime().invalidateProfile();
            owner.setChanged();
            return true;
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("contraption_disassembly_failed", owner.networkId(),
                    owner.endpoint(), null, owner.currentTickForDiagnostics(),
                    "entity=" + entityUuid + " reason=" + reason, exception);
            return false;
        }
    }

    private ControlledContraptionEntity resolveEntity(RadarControllerBlockEntity owner, ServerLevel level) {
        if (entity != null && entity.isAlive()) return entity;
        entity = null;
        if (entityUuid == null) return null;
        Entity found = level.getEntity(entityUuid);
        if (found instanceof ControlledContraptionEntity controlled && controlled.isAlive()) {
            entity = controlled;
            missingEntityTicks = 0;
            missingEntityLogged = false;
            RadarDiagnostics.log("contraption_recovered_after_load", owner.networkId(), owner.endpoint(),
                    null, owner.currentTickForDiagnostics(), "entity=" + entityUuid + " panels=" + panelCount);
            return controlled;
        }
        return null;
    }

    public void sendCurrentTiltTo(ServerPlayer player, ControlledContraptionEntity candidate) {
        if (!tiltActive || !owns(candidate) || !candidate.isAlive()) return;
        int remaining = Math.max(0, tiltDuration - tiltElapsed);
        sendTilt(player, candidate, candidate.getAngle(1.0F), tiltTarget, remaining);
    }

    private static void sendTilt(ControlledContraptionEntity entity, float current,
                                 float target, int remainingTicks) {
        PacketDistributor.sendToPlayersTrackingEntity(entity,
                new RadarPanelTiltPayload(entity.getId(), current, target, remainingTicks));
    }

    private static void sendTilt(ServerPlayer player, ControlledContraptionEntity entity,
                                 float current, float target, int remainingTicks) {
        PacketDistributor.sendToPlayer(player,
                new RadarPanelTiltPayload(entity.getId(), current, target, remainingTicks));
    }

    private void clearRuntime() {
        assembled = false;
        entityUuid = null;
        panelCount = 0;
        entity = null;
        tiltActive = false;
        tiltStart = 0.0F;
        tiltTarget = 0.0F;
        tiltDuration = 0;
        tiltElapsed = 0;
        missingEntityTicks = 0;
        missingEntityLogged = false;
    }

    private void logAssembly(RadarControllerBlockEntity owner, RadarAssemblyFinder.Failure failure, int count) {
        RadarDiagnostics.log(failure == RadarAssemblyFinder.Failure.NONE
                        ? "assembly_search_completed" : "assembly_search_rejected",
                owner.networkId(), owner.endpoint(), null, owner.currentTickForDiagnostics(),
                "mode=" + owner.mode() + " facing=" + owner.getBlockState().getValue(RadarControllerBlock.FACING)
                        + " panels=" + count + " reason=" + failure.name().toLowerCase());
    }

    private static void tell(net.minecraft.world.entity.player.Player player,
                             RadarAssemblyFinder.Failure failure, int countOrLimit) {
        if (player == null) return;
        if (failure == RadarAssemblyFinder.Failure.NONE) {
            player.displayClientMessage(Component.translatable("message.electroradar.assembly.success",
                    countOrLimit), true);
        } else if (failure == RadarAssemblyFinder.Failure.TOO_MANY_PANELS) {
            player.displayClientMessage(Component.translatable(failure.translationKey(), countOrLimit), true);
        } else {
            player.displayClientMessage(Component.translatable(failure.translationKey()), true);
        }
    }

    /** Create вызывает этот метод только после восстановления entity из её сохранённого UUID. */
    public void attach(RadarControllerBlockEntity owner, ControlledContraptionEntity controlled) {
        if (!(controlled.getContraption() instanceof RadarPanelContraption)) return;
        // Не усыновляем чужую или осиротевшую entity по совпавшему controllerPos. Связь
        // восстанавливается только по UUID, сохранённому вместе с контроллером.
        if (entityUuid == null || !entityUuid.equals(controlled.getUUID())) return;
        entityUuid = controlled.getUUID();
        entity = controlled;
        assembled = true;
        missingEntityTicks = 0;
        missingEntityLogged = false;
        owner.setChanged();
    }

    public boolean isAttachedTo(AbstractContraptionEntity controlled) {
        return controlled instanceof ControlledContraptionEntity entity
                && entityUuid != null && entityUuid.equals(entity.getUUID());
    }
}
