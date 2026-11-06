package com.electroradar.radar.controller;

import com.electroradar.radar.spatial.RadarSpatial;

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
 * тип/размер апертуры и фаза анимации; блоки сериализует сама contraption.
 */
final class RadarAssemblyController {
    private static final String ASSEMBLED_TAG = "Assembled";
    private static final String ENTITY_UUID_TAG = "PanelContraptionUuid";
    private static final String PANEL_COUNT_TAG = "PanelCount";
    private static final String APERTURE_KIND_TAG = "ApertureKind";
    private static final String APERTURE_COUNT_TAG = "ApertureCount";
    private static final String TILT_ACTIVE_TAG = "TiltActive";
    private static final String TILT_START_TAG = "TiltStart";
    private static final String TILT_TARGET_TAG = "TiltTarget";
    private static final String TILT_DURATION_TAG = "TiltDuration";
    private static final String TILT_ELAPSED_TAG = "TiltElapsed";
    private static final int MISSING_ENTITY_CONFIRM_TICKS = 5;

    private boolean assembled;
    private UUID entityUuid;
    private int apertureCount;
    private RadarApertureKind apertureKind = RadarApertureKind.PANEL;
    private transient ControlledContraptionEntity entity;
    private transient RadarControllerBlockEntity ownerEntity;
    private transient ControlledContraptionEntity externalCandidate;
    private transient java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> externalBlocks = java.util.Map.of();
    private transient boolean externalConfirmed;
    private transient long externalTick = Long.MIN_VALUE;
    private transient BlockPos externalOwnerPosition;
    private transient java.util.UUID externalNonce;
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
        apertureCount = Math.max(0, tag.contains(APERTURE_COUNT_TAG)
                ? tag.getInt(APERTURE_COUNT_TAG) : tag.getInt(PANEL_COUNT_TAG));
        apertureKind = tag.contains(APERTURE_KIND_TAG)
                ? RadarApertureKind.fromSavedName(tag.getString(APERTURE_KIND_TAG))
                : RadarApertureKind.PANEL;
        tiltActive = tag.getBoolean(TILT_ACTIVE_TAG);
        tiltStart = tag.getFloat(TILT_START_TAG);
        tiltTarget = tag.getFloat(TILT_TARGET_TAG);
        tiltDuration = Math.clamp(tag.getInt(TILT_DURATION_TAG), 0, 20);
        tiltElapsed = Math.clamp(tag.getInt(TILT_ELAPSED_TAG), 0, Math.max(0, tiltDuration));
        if (entityUuid == null) {
            assembled = false;
            apertureCount = 0;
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
        tag.putInt(PANEL_COUNT_TAG, apertureCount);
        tag.putInt(APERTURE_COUNT_TAG, apertureCount);
        tag.putString(APERTURE_KIND_TAG, apertureKind.serializedName());
        tag.putBoolean(TILT_ACTIVE_TAG, tiltActive);
        tag.putFloat(TILT_START_TAG, tiltStart);
        tag.putFloat(TILT_TARGET_TAG, tiltTarget);
        tag.putInt(TILT_DURATION_TAG, tiltDuration);
        tag.putInt(TILT_ELAPSED_TAG, tiltElapsed);
    }

    public void onLoad(RadarControllerBlockEntity owner) {
        ownerEntity = owner;
        entity = null;
        missingEntityTicks = 0;
        missingEntityLogged = false;
        lastFacing = owner.getBlockState().getValue(RadarControllerBlock.FACING);
    }

    /** PANEL зависит от ориентации корпуса; OVERVIEW сохраняет фиксированную ориентацию. */
    public boolean isReadyForScanning(RadarControllerBlockEntity owner) {
        return assembled && apertureCount > 0 && (apertureKind == RadarApertureKind.OVERVIEW
                || lastFacing == null
                || lastFacing == owner.getBlockState().getValue(RadarControllerBlock.FACING));
    }

    /** UUID учитывается и во время восстановления entity после загрузки чанка. */
    public boolean hasActiveAssembly() {
        return assembled || entityUuid != null;
    }

    public int apertureCount() { return apertureCount; }
    public RadarApertureKind apertureKind() { return apertureKind; }

    public boolean owns(ControlledContraptionEntity candidate, RadarControllerBlockEntity owner) {
        ownerEntity = owner;
        return owns(candidate);
    }
    public boolean owns(ControlledContraptionEntity candidate) {
        return ownerEntity != null && entityUuid != null && candidate.getUUID().equals(entityUuid)
                && candidate.getContraption() instanceof RadarPanelContraption
                && candidate.level() == ownerEntity.getLevel()
                && candidate.blockPosition().equals(ownerEntity.getBlockPos().above())
                && RadarSpatial.sameSpace(candidate.level(), ownerEntity.getBlockPos(), candidate.blockPosition());
    }

    public void tick(RadarControllerBlockEntity owner, ServerLevel level) {
        ownerEntity = owner;
        Direction facing = owner.getBlockState().getValue(RadarControllerBlock.FACING);
        if (lastFacing == null) {
            lastFacing = facing;
        } else if (lastFacing != facing && prepareForControllerRotation(owner, level)) {
            // Внешняя смена BlockState могла обойти предварительную разборку ключом.
            // При отказе lastFacing остаётся прежним: следующий тик повторит попытку.
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
                apertureCount = 0;
                tiltActive = false;
                owner.setChanged();
            }
            return;
        }
        if (attached == null || !tiltActive || lastFacing != facing) return;

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

    public boolean canTransfer(RadarControllerBlockEntity owner, ServerLevel level) {
        ownerEntity = owner;
        if (!hasActiveAssembly()) return true;
        var current = resolveEntity(owner, level);
        return current != null ? restorable(owner, level, (RadarPanelContraption)current.getContraption()) : externalRestored(owner, level);
    }

    /** Normalize before Simulated computes its restoration transform. */
    public boolean normalizeForTransfer(RadarControllerBlockEntity owner, ServerLevel level) {
        ownerEntity = owner;
        if (!hasActiveAssembly()) return true;
        ControlledContraptionEntity current = resolveEntity(owner, level);
        if (current == null) return externalRestored(owner, level);
        var aperture = (RadarPanelContraption) current.getContraption();
        if (!restorable(owner, level, aperture)) return false;
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> expected = new java.util.HashMap<>();
        for (var block : aperture.getBlocks().entrySet()) expected.put(owner.getBlockPos().above().offset(block.getKey()),
                block.getValue().state());
        externalCandidate = current; externalBlocks = java.util.Map.copyOf(expected); externalConfirmed = false;
        externalTick = level.getGameTime(); externalOwnerPosition = owner.getBlockPos().immutable(); externalNonce = java.util.UUID.randomUUID();
        current.setAngle(0); tiltActive = false;
        owner.getPersistentData().putBoolean("electroradar_resume_assembly", true);
        return true;
    }

    public boolean confirmExternalDisassembly(RadarControllerBlockEntity owner, ServerLevel level) {
        externalConfirmed = externalCandidate != null && !externalCandidate.isAlive()
                && !externalBlocks.isEmpty() && externalContext(owner, level) && restoredBlocks(level);
        return externalConfirmed;
    }
    public void releaseExternalProof() {
        externalCandidate = null; externalBlocks = java.util.Map.of(); externalConfirmed = false;
        externalTick = Long.MIN_VALUE; externalOwnerPosition = null; externalNonce = null;
    }
    private boolean restorable(RadarControllerBlockEntity owner, ServerLevel level, RadarPanelContraption aperture) {
        for (BlockPos local : aperture.getBlocks().keySet()) {
            BlockPos position = owner.getBlockPos().above().offset(local);
            if (!level.hasChunkAt(position)) return false;
            var state = level.getBlockState(position);
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }
    private static net.minecraft.world.level.block.state.BlockState physicalState(net.minecraft.world.level.block.state.BlockState state) {
        return state.hasProperty(RadarPanelBlock.PROFILE) ? state.setValue(RadarPanelBlock.PROFILE, RadarPanelBlock.PanelProfile.SURFACE) : state;
    }
    private boolean restoredBlocks(ServerLevel level) {
        return externalBlocks.entrySet().stream().allMatch(e -> level.hasChunkAt(e.getKey())
                && physicalState(level.getBlockState(e.getKey())).equals(physicalState(e.getValue())));
    }
    private boolean externalContext(RadarControllerBlockEntity owner, ServerLevel level) {
        return externalNonce != null && ownerEntity == owner && externalOwnerPosition != null
                && externalOwnerPosition.equals(owner.getBlockPos()) && level.getGameTime() == externalTick;
    }
    private boolean externalRestored(RadarControllerBlockEntity owner, ServerLevel level) {
        return externalConfirmed && externalContext(owner, level) && externalCandidate != null && !externalCandidate.isAlive()
                && entityUuid != null && entityUuid.equals(externalCandidate.getUUID()) && restoredBlocks(level);
    }

    /** Returns the restored panel positions; never adopts or disassembles another controller's entity. */
    public java.util.List<BlockPos> detachForTransfer(RadarControllerBlockEntity owner, ServerLevel level) {
        if (!hasActiveAssembly()) return java.util.List.of();
        if (!canTransfer(owner, level)) throw new IllegalStateException("Radar aperture is not loaded or does not belong to controller " + owner.getBlockPos());
        normalizeForTransfer(owner, level);
        ControlledContraptionEntity current = resolveEntity(owner, level);
        java.util.List<BlockPos> panels = new java.util.ArrayList<>();
        if (current == null) panels.addAll(externalBlocks.keySet());
        if (current != null && owns(current) && current.getContraption() instanceof RadarPanelContraption aperture) {
            for (BlockPos local : aperture.getBlocks().keySet()) panels.add(owner.getBlockPos().above().offset(local));
            current.setAngle(0);
            current.disassemble();
            aperture.refreshRestoredAperture(level, owner.getBlockPos());
            if (!confirmExternalDisassembly(owner, level)) throw new IllegalStateException("Radar aperture restoration failed: " + owner.getBlockPos());
        }
        clearRuntime(); owner.runtime().invalidateProfile(); owner.setChanged();
        return java.util.List.copyOf(panels);
    }

    public void toggleAssembly(RadarControllerBlockEntity owner, ServerLevel level, net.minecraft.world.entity.player.Player player) {
        if (assembled || entityUuid != null) {
            ControlledContraptionEntity current = resolveEntity(owner, level);
            if (current == null) {
                tell(player, RadarAssemblyFinder.Failure.ENTITY_UNAVAILABLE, 0);
                logAssembly(owner, RadarAssemblyFinder.Failure.ENTITY_UNAVAILABLE,
                        apertureCount, apertureKind);
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
                level, owner.getBlockPos(), facing, parameters.maxPanels(), parameters.overview().maxModules());
        if (!discovery.valid()) {
            logAssembly(owner, discovery.failure(), discovery.apertureCount(), discovery.apertureKind());
            tell(player, discovery.failure(), discovery.apertureKind() == RadarApertureKind.OVERVIEW
                    ? parameters.overview().maxModules() : parameters.maxPanels());
            return;
        }
        logAssembly(owner, RadarAssemblyFinder.Failure.NONE,
                discovery.apertureCount(), discovery.apertureKind());

        RadarContraptionAdapter.Result result = RadarContraptionAdapter.assemble(
                level, owner, owner.mode(), discovery);
        if (!result.success()) {
            logAssembly(owner, result.failure(), discovery.apertureCount(), discovery.apertureKind());
            tell(player, result.failure(), discovery.apertureKind() == RadarApertureKind.OVERVIEW
                    ? parameters.overview().maxModules() : parameters.maxPanels());
            return;
        }

        RadarApertureKind previousKind = apertureKind;
        entity = result.entity();
        entityUuid = entity.getUUID();
        assembled = true;
        apertureKind = discovery.apertureKind();
        apertureCount = discovery.apertureCount();
        missingEntityTicks = 0;
        missingEntityLogged = false;
        tiltStart = 0.0F;
        tiltTarget = apertureKind == RadarApertureKind.OVERVIEW
                ? 0.0F : (float) owner.mode().panelTiltDegrees(facing);
        tiltDuration = (int) Math.ceil(Math.abs(tiltTarget - tiltStart));
        tiltElapsed = 0;
        tiltActive = tiltDuration > 0;
        owner.setChanged();
        RadarServerRuntime runtime = owner.runtime();
        if (previousKind != apertureKind) runtime.apertureKindChanged(owner);
        else runtime.invalidateProfile();
        if (tiltActive) sendTilt(entity, tiltStart, tiltTarget, tiltDuration);
        RadarDiagnostics.log("contraption_assembled", owner.networkId(), owner.endpoint(), null,
                owner.currentTickForDiagnostics(), "entity=" + entityUuid + " mode=" + owner.mode()
                        + " aperture=" + apertureKind.serializedName() + " count=" + apertureCount
                        + " targetAngle=" + tiltTarget);
        tellSuccess(player, apertureKind, apertureCount);
    }

    public void prepareForBlockRemoval(RadarControllerBlockEntity owner, ServerLevel level) {
        if (entityUuid == null) return;
        ControlledContraptionEntity current = resolveEntity(owner, level);
        if (current != null) {
            disassemble(owner, level, "controller_removed");
        } else {
            RadarDiagnostics.log("contraption_unavailable_on_controller_removal", owner.networkId(),
                    owner.endpoint(), null, owner.currentTickForDiagnostics(),
                    "entity=" + entityUuid + " action=defer_disassembly_to_entity_tick");
            // Панели хранятся только в entity. Её Pre-tick после загрузки восстановит их,
            // если установленного владельца уже нет; создавать копии здесь нельзя.
            clearRuntime();
        }
    }

    /** Поворот корпуса OVERVIEW не меняет апертуру; PANEL сначала возвращается в мир. */
    public boolean prepareForControllerRotation(RadarControllerBlockEntity owner, ServerLevel level) {
        return apertureKind == RadarApertureKind.OVERVIEW || !hasActiveAssembly()
                || disassemble(owner, level, "controller_rotated");
    }

    private boolean disassemble(RadarControllerBlockEntity owner, ServerLevel level, String reason) {
        ControlledContraptionEntity current = resolveEntity(owner, level);
        if (current == null) return false;
        try {
            current.setAngle(0.0F);
            if (current.getContraption() instanceof RadarPanelContraption panels) {
                current.disassemble();
                panels.refreshRestoredAperture(level, owner.getBlockPos());
            } else {
                current.disassemble();
                RadarDiagnostics.log("contraption_type_mismatch_on_disassemble", owner.networkId(),
                        owner.endpoint(), null, owner.currentTickForDiagnostics(), "entity=" + entityUuid);
            }
            RadarDiagnostics.log("contraption_disassembled", owner.networkId(), owner.endpoint(), null,
                    owner.currentTickForDiagnostics(), "entity=" + entityUuid + " aperture="
                            + apertureKind.serializedName() + " count=" + apertureCount
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
        ownerEntity = owner;
        if (entity != null && entity.isAlive() && owns(entity)) return entity;
        entity = null;
        if (entityUuid == null) return null;
        Entity found = level.getEntity(entityUuid);
        if (found instanceof ControlledContraptionEntity controlled && controlled.isAlive() && owns(controlled)) {
            entity = controlled;
            missingEntityTicks = 0;
            missingEntityLogged = false;
            RadarDiagnostics.log("contraption_recovered_after_load", owner.networkId(), owner.endpoint(),
                    null, owner.currentTickForDiagnostics(), "entity=" + entityUuid + " aperture="
                            + apertureKind.serializedName() + " count=" + apertureCount);
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
        apertureCount = 0;
        entity = null;
        releaseExternalProof();
        tiltActive = false;
        tiltStart = 0.0F;
        tiltTarget = 0.0F;
        tiltDuration = 0;
        tiltElapsed = 0;
        missingEntityTicks = 0;
        missingEntityLogged = false;
    }

    private void logAssembly(RadarControllerBlockEntity owner, RadarAssemblyFinder.Failure failure,
                             int count, RadarApertureKind kind) {
        RadarDiagnostics.log(failure == RadarAssemblyFinder.Failure.NONE
                        ? "assembly_search_completed" : "assembly_search_rejected",
                owner.networkId(), owner.endpoint(), null, owner.currentTickForDiagnostics(),
                "mode=" + owner.mode() + " facing=" + owner.getBlockState().getValue(RadarControllerBlock.FACING)
                        + " aperture=" + kind.serializedName() + " count=" + count
                        + " reason=" + failure.name().toLowerCase());
    }

    private static void tell(net.minecraft.world.entity.player.Player player,
                             RadarAssemblyFinder.Failure failure, int countOrLimit) {
        if (player == null) return;
        if (failure == RadarAssemblyFinder.Failure.NONE) {
            player.displayClientMessage(Component.translatable("message.electroradar.assembly.success",
                    countOrLimit), true);
        } else if (failure == RadarAssemblyFinder.Failure.TOO_MANY_PANELS
                || failure == RadarAssemblyFinder.Failure.TOO_MANY_OVERVIEW_MODULES) {
            player.displayClientMessage(Component.translatable(failure.translationKey(), countOrLimit), true);
        } else {
            player.displayClientMessage(Component.translatable(failure.translationKey()), true);
        }
    }

    private static void tellSuccess(net.minecraft.world.entity.player.Player player,
                                    RadarApertureKind kind, int count) {
        if (player == null) return;
        String key = kind == RadarApertureKind.OVERVIEW
                ? "message.electroradar.assembly.overview_success"
                : "message.electroradar.assembly.success";
        player.displayClientMessage(Component.translatable(key, count), true);
    }

    /** Create вызывает этот метод только после восстановления entity из её сохранённого UUID. */
    public void attach(RadarControllerBlockEntity owner, ControlledContraptionEntity controlled) {
        ownerEntity = owner;
        if (!(controlled.getContraption() instanceof RadarPanelContraption)) return;
        // Не усыновляем чужую или осиротевшую entity по совпавшему controllerPos. Связь
        // восстанавливается только по UUID, сохранённому вместе с контроллером.
        if (!owns(controlled)) return;
        entityUuid = controlled.getUUID();
        entity = controlled;
        assembled = true;
        missingEntityTicks = 0;
        missingEntityLogged = false;
        owner.setChanged();
    }

    public boolean isAttachedTo(AbstractContraptionEntity controlled) {
        return controlled instanceof ControlledContraptionEntity candidate && owns(candidate);
    }
}
