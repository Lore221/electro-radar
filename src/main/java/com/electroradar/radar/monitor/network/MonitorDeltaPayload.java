package com.electroradar.radar.monitor.network;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.network.TargetRef;

/** Атомарная серия абсолютных изменений от fromSequence до toSequence одной viewGeneration. */
public record MonitorDeltaPayload(
        UUID displayId, BlockPos rootPosition, ResourceLocation dimension, UUID loadId,
        long viewGeneration, long fromSequence, long toSequence, long structureVersion,
        long publishedEpoch, long revision, long measurementRevision, long observedServerTick,
        boolean linked, boolean selectedTargetUnavailable, TargetRef selectedTarget,
        int partIndex, int partCount, List<MonitorTargetData> upserts,
        List<TargetRef> removes, List<MonitorVisualPhase> visualPhases
) implements CustomPacketPayload {
    public static final int RECORDS_PER_PART = 192;
    public static final int MAX_PHASES_PER_PART = 128;
    public static final int MAX_PARTS = 4_096;
    public static final Type<MonitorDeltaPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_delta"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorDeltaPayload> STREAM_CODEC =
            StreamCodec.of(MonitorDeltaPayload::write, MonitorDeltaPayload::read);

    public MonitorDeltaPayload {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        upserts = List.copyOf(upserts);
        removes = List.copyOf(removes);
        visualPhases = List.copyOf(visualPhases);
        if (viewGeneration < 0 || fromSequence < 0 || toSequence <= fromSequence
                || structureVersion < 0 || publishedEpoch < 0 || revision < 0
                || measurementRevision < 0 || observedServerTick < 0
                || partCount < 1 || partCount > MAX_PARTS || partIndex < 0 || partIndex >= partCount
                || upserts.size() > RECORDS_PER_PART || removes.size() > RECORDS_PER_PART
                || visualPhases.size() > MAX_PHASES_PER_PART) {
            throw new IllegalArgumentException("Invalid monitor delta part bounds");
        }
    }

    public static List<MonitorDeltaPayload> split(MonitorSnapshotData snapshot, long fromSequence,
                                                   List<MonitorTargetData> upserts,
                                                   List<TargetRef> removes) {
        int count = Math.max(1, Math.max(Math.max((upserts.size() + RECORDS_PER_PART - 1) / RECORDS_PER_PART,
                (removes.size() + RECORDS_PER_PART - 1) / RECORDS_PER_PART),
                (snapshot.visualPhases().size() + MAX_PHASES_PER_PART - 1) / MAX_PHASES_PER_PART));
        if (count > MAX_PARTS) throw new IllegalArgumentException("Monitor delta exceeds multipart limit");
        java.util.ArrayList<MonitorDeltaPayload> parts = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int upsertFrom = Math.min(upserts.size(), index * RECORDS_PER_PART);
            int removeFrom = Math.min(removes.size(), index * RECORDS_PER_PART);
            int phaseFrom = Math.min(snapshot.visualPhases().size(), index * MAX_PHASES_PER_PART);
            parts.add(new MonitorDeltaPayload(snapshot.displayId(), snapshot.rootPosition(), snapshot.dimension(),
                    snapshot.loadId(), snapshot.viewGeneration(), fromSequence, snapshot.sequence(),
                    snapshot.structureVersion(), snapshot.publishedEpoch(), snapshot.revision(),
                    snapshot.measurementRevision(), snapshot.observedServerTick(), snapshot.linked(),
                    snapshot.selectedTargetUnavailable(), snapshot.selectedTarget(),
                    index, count,
                    upserts.subList(upsertFrom, Math.min(upserts.size(), upsertFrom + RECORDS_PER_PART)),
                    removes.subList(removeFrom, Math.min(removes.size(), removeFrom + RECORDS_PER_PART)),
                    snapshot.visualPhases().subList(phaseFrom,
                            Math.min(snapshot.visualPhases().size(), phaseFrom + MAX_PHASES_PER_PART))));
        }
        return List.copyOf(parts);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorDeltaPayload payload) {
        buffer.writeUUID(payload.displayId);
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.loadId);
        buffer.writeVarLong(payload.viewGeneration);
        buffer.writeVarLong(payload.fromSequence);
        buffer.writeVarLong(payload.toSequence);
        buffer.writeVarLong(payload.structureVersion);
        buffer.writeVarLong(payload.publishedEpoch);
        buffer.writeVarLong(payload.revision);
        buffer.writeVarLong(payload.measurementRevision);
        buffer.writeVarLong(payload.observedServerTick);
        buffer.writeBoolean(payload.linked);
        buffer.writeBoolean(payload.selectedTargetUnavailable);
        MonitorSnapshotPayload.writeTargetRef(buffer, payload.selectedTarget);
        buffer.writeVarInt(payload.partIndex);
        buffer.writeVarInt(payload.partCount);
        buffer.writeVarInt(payload.visualPhases.size());
        for (MonitorVisualPhase phase : payload.visualPhases) MonitorSnapshotPayload.writePhase(buffer, phase);
        buffer.writeVarInt(payload.upserts.size());
        for (MonitorTargetData target : payload.upserts) MonitorSnapshotPayload.writeTargetData(buffer, target);
        buffer.writeVarInt(payload.removes.size());
        for (TargetRef target : payload.removes) MonitorSnapshotPayload.writeTargetRef(buffer, target);
    }

    private static MonitorDeltaPayload read(RegistryFriendlyByteBuf buffer) {
        UUID displayId = buffer.readUUID();
        BlockPos root = buffer.readBlockPos();
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID loadId = buffer.readUUID();
        long generation = buffer.readVarLong();
        long from = buffer.readVarLong();
        long to = buffer.readVarLong();
        long structureVersion = buffer.readVarLong();
        long epoch = buffer.readVarLong();
        long revision = buffer.readVarLong();
        long measurementRevision = buffer.readVarLong();
        long serverTick = buffer.readVarLong();
        boolean linked = buffer.readBoolean();
        boolean selectionUnavailable = buffer.readBoolean();
        TargetRef selected = MonitorSnapshotPayload.readTargetRef(buffer);
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        int phaseCount = buffer.readVarInt();
        if (phaseCount < 0 || phaseCount > MAX_PHASES_PER_PART) {
            throw new IllegalArgumentException("Monitor delta has too many visual phases");
        }
        java.util.ArrayList<MonitorVisualPhase> phases = new java.util.ArrayList<>(phaseCount);
        for (int i = 0; i < phaseCount; i++) phases.add(MonitorSnapshotPayload.readPhase(buffer));
        int upsertCount = buffer.readVarInt();
        if (upsertCount < 0 || upsertCount > RECORDS_PER_PART) {
            throw new IllegalArgumentException("Monitor delta exceeds its upsert limit");
        }
        java.util.ArrayList<MonitorTargetData> upserts = new java.util.ArrayList<>(upsertCount);
        for (int i = 0; i < upsertCount; i++) upserts.add(MonitorSnapshotPayload.readTargetData(buffer));
        int removeCount = buffer.readVarInt();
        if (removeCount < 0 || removeCount > RECORDS_PER_PART) {
            throw new IllegalArgumentException("Monitor delta exceeds its remove limit");
        }
        java.util.ArrayList<TargetRef> removes = new java.util.ArrayList<>(removeCount);
        for (int i = 0; i < removeCount; i++) {
            TargetRef target = MonitorSnapshotPayload.readTargetRef(buffer);
            if (target == null) throw new IllegalArgumentException("Monitor delta remove cannot be null");
            removes.add(target);
        }
        return new MonitorDeltaPayload(displayId, root, dimension, loadId, generation, from, to,
                structureVersion, epoch, revision, measurementRevision, serverTick, linked,
                selectionUnavailable, selected, index, count, upserts, removes, phases);
    }
}
