package com.electroradar.radar.monitor.network;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.TargetRef;

/**
 * Одна ограниченная часть экранного снимка, отправляемая только наблюдателям дисплея или
 * конкретному игроку при открытии GUI. Большие сети разбиваются на части и применяются на
 * клиенте только после получения всего набора; ни одна цель не отбрасывается молча.
 */
public record MonitorSnapshotPayload(
        UUID displayId,
        BlockPos rootPosition,
        ResourceLocation dimension,
        UUID loadId,
        long structureVersion,
        long publishedEpoch,
        long revision,
        boolean linked,
        boolean selectedTargetUnavailable,
        TargetRef selectedTarget,
        int partIndex,
        int partCount,
        List<MonitorTargetData> targets
) implements CustomPacketPayload {
    public static final int TARGETS_PER_PART = 192;
    public static final Type<MonitorSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(MonitorSnapshotPayload::write, MonitorSnapshotPayload::read);

    public MonitorSnapshotPayload {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = rootPosition.immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        targets = List.copyOf(targets);
        if (structureVersion < 0 || publishedEpoch < 0 || revision < 0
                || partCount < 1 || partCount > 65_536
                || partIndex < 0 || partIndex >= partCount || targets.size() > TARGETS_PER_PART) {
            throw new IllegalArgumentException("Invalid monitor snapshot part bounds");
        }
    }

    /** Разбивает готовый снимок на части с общей версией и неизменным порядком целей. */
    public static List<MonitorSnapshotPayload> split(MonitorSnapshotData snapshot) {
        int count = Math.max(1, (snapshot.targets().size() + TARGETS_PER_PART - 1) / TARGETS_PER_PART);
        java.util.ArrayList<MonitorSnapshotPayload> parts = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int from = index * TARGETS_PER_PART;
            int to = Math.min(snapshot.targets().size(), from + TARGETS_PER_PART);
            parts.add(new MonitorSnapshotPayload(snapshot.displayId(), snapshot.rootPosition(),
                    snapshot.dimension(), snapshot.loadId(), snapshot.structureVersion(),
                    snapshot.publishedEpoch(), snapshot.revision(), snapshot.linked(),
                    snapshot.selectedTargetUnavailable(), snapshot.selectedTarget(), index, count,
                    snapshot.targets().subList(from, to)));
        }
        return List.copyOf(parts);
    }

    /** Собирает завершённую на клиенте серию частей обратно в неизменяемую запись. */
    public MonitorSnapshotData asSnapshot(List<MonitorTargetData> allTargets) {
        return new MonitorSnapshotData(displayId, rootPosition, dimension, loadId,
                structureVersion, publishedEpoch, revision, linked,
                selectedTargetUnavailable, selectedTarget, allTargets);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorSnapshotPayload payload) {
        buffer.writeUUID(payload.displayId);
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.loadId);
        buffer.writeVarLong(payload.structureVersion);
        buffer.writeVarLong(payload.publishedEpoch);
        buffer.writeVarLong(payload.revision);
        buffer.writeBoolean(payload.linked);
        buffer.writeBoolean(payload.selectedTargetUnavailable);
        writeTargetRef(buffer, payload.selectedTarget);
        buffer.writeVarInt(payload.partIndex);
        buffer.writeVarInt(payload.partCount);
        buffer.writeVarInt(payload.targets.size());
        for (MonitorTargetData target : payload.targets) {
            writeTargetRef(buffer, target.target());
            buffer.writeBoolean(target.detectedThisWindow());
            buffer.writeBoolean(target.hasPosition());
            if (target.hasPosition()) {
                buffer.writeDouble(target.x());
                buffer.writeDouble(target.y());
                buffer.writeDouble(target.z());
                buffer.writeUtf(target.entityTypeId(), 256);
            }
        }
    }

    private static MonitorSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        UUID displayId = buffer.readUUID();
        BlockPos root = buffer.readBlockPos();
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID loadId = buffer.readUUID();
        long structureVersion = buffer.readVarLong();
        long epoch = buffer.readVarLong();
        long revision = buffer.readVarLong();
        boolean linked = buffer.readBoolean();
        boolean selectionUnavailable = buffer.readBoolean();
        TargetRef selected = readTargetRef(buffer);
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        int targetCount = buffer.readVarInt();
        if (targetCount < 0 || targetCount > TARGETS_PER_PART) {
            throw new IllegalArgumentException("Monitor snapshot part exceeds its target limit");
        }
        java.util.ArrayList<MonitorTargetData> targets = new java.util.ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            TargetRef ref = readTargetRef(buffer);
            boolean detected = buffer.readBoolean();
            boolean hasPosition = buffer.readBoolean();
            double x = 0;
            double y = 0;
            double z = 0;
            String typeId = "";
            if (hasPosition) {
                x = buffer.readDouble();
                y = buffer.readDouble();
                z = buffer.readDouble();
                typeId = buffer.readUtf(256);
            }
            targets.add(new MonitorTargetData(ref, detected, hasPosition, x, y, z, typeId));
        }
        return new MonitorSnapshotPayload(displayId, root, dimension, loadId,
                structureVersion, epoch, revision, linked, selectionUnavailable,
                selected, index, count, targets);
    }

    static void writeTargetRef(RegistryFriendlyByteBuf buffer, TargetRef target) {
        buffer.writeBoolean(target != null);
        if (target == null) {
            return;
        }
        buffer.writeResourceLocation(target.dimension().location());
        buffer.writeVarInt(target.sourceKind().ordinal());
        buffer.writeUUID(target.id());
    }

    static TargetRef readTargetRef(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        ResourceLocation dimension = buffer.readResourceLocation();
        int source = buffer.readVarInt();
        if (source < 0 || source >= TargetRef.SourceKind.values().length) {
            throw new IllegalArgumentException("Unknown monitor target source kind");
        }
        return new TargetRef(ResourceKey.create(Registries.DIMENSION, dimension),
                TargetRef.SourceKind.values()[source], buffer.readUUID());
    }
}
