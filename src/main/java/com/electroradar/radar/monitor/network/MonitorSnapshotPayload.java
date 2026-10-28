package com.electroradar.radar.monitor.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarSectorOrder;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Одна часть полного снимка монитора. Части одной серии имеют общий заголовок и применяются
 * на клиенте только целиком; размер каждой части ограничен.
 *
 * <p>{@code includesGeometry} говорит, несёт ли серия профили радаров. Без геометрии клиент
 * оставляет у себя профили той же {@code geometryVersion}, а фазы применяет к ним.</p>
 */
public record MonitorSnapshotPayload(
        UUID displayId,
        BlockPos rootPosition,
        ResourceLocation dimension,
        UUID loadId,
        long sequence,
        long geometryVersion,
        long observedServerTick,
        boolean linked,
        boolean selectedTargetUnavailable,
        TargetRef selectedTarget,
        boolean includesGeometry,
        int partIndex,
        int partCount,
        List<MonitorTargetData> targets,
        List<RadarVisualProfile> visualProfiles,
        List<MonitorVisualPhase> visualPhases
) implements CustomPacketPayload {
    public static final int TARGETS_PER_PART = 192;
    public static final int PROFILES_PER_PART = 8;
    public static final int PHASES_PER_PART = 256;
    public static final int MAX_PARTS = 4_096;
    public static final Type<MonitorSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(MonitorSnapshotPayload::write, MonitorSnapshotPayload::read);

    public MonitorSnapshotPayload {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        targets = List.copyOf(targets);
        visualProfiles = List.copyOf(visualProfiles);
        visualPhases = List.copyOf(visualPhases);
        if (sequence < 0 || geometryVersion < 0 || observedServerTick < 0
                || partCount < 1 || partCount > MAX_PARTS || partIndex < 0 || partIndex >= partCount
                || targets.size() > TARGETS_PER_PART || visualProfiles.size() > PROFILES_PER_PART
                || visualPhases.size() > PHASES_PER_PART
                || !visualProfiles.isEmpty() && (!includesGeometry || !linked)) {
            throw new IllegalArgumentException("Invalid monitor snapshot part bounds");
        }
    }

    /** Разбивает снимок на части; профили включаются, только если {@code includeGeometry}. */
    public static List<MonitorSnapshotPayload> split(MonitorSnapshotData snapshot, boolean includeGeometry) {
        List<RadarVisualProfile> profiles = includeGeometry ? snapshot.visualProfiles() : List.of();
        int count = Math.max(1, Math.max(parts(snapshot.targets().size(), TARGETS_PER_PART),
                Math.max(parts(profiles.size(), PROFILES_PER_PART),
                        parts(snapshot.visualPhases().size(), PHASES_PER_PART))));
        if (count > MAX_PARTS) throw new IllegalArgumentException("Monitor snapshot exceeds multipart limit");
        ArrayList<MonitorSnapshotPayload> parts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            parts.add(new MonitorSnapshotPayload(snapshot.displayId(), snapshot.rootPosition(),
                    snapshot.dimension(), snapshot.loadId(), snapshot.sequence(), snapshot.geometryVersion(),
                    snapshot.observedServerTick(), snapshot.linked(), snapshot.selectedTargetUnavailable(),
                    snapshot.selectedTarget(), includeGeometry, index, count,
                    slice(snapshot.targets(), index, TARGETS_PER_PART),
                    slice(profiles, index, PROFILES_PER_PART),
                    slice(snapshot.visualPhases(), index, PHASES_PER_PART)));
        }
        return List.copyOf(parts);
    }

    private static int parts(int size, int perPart) {
        return (size + perPart - 1) / perPart;
    }

    private static <T> List<T> slice(List<T> values, int index, int perPart) {
        int from = Math.min(values.size(), index * perPart);
        return values.subList(from, Math.min(values.size(), from + perPart));
    }

    /** Заголовок серии одинаков во всех её частях; по нему клиент отбрасывает чужие части. */
    public boolean sameSeries(MonitorSnapshotPayload other) {
        return sequence == other.sequence && partCount == other.partCount
                && geometryVersion == other.geometryVersion && includesGeometry == other.includesGeometry
                && observedServerTick == other.observedServerTick && linked == other.linked
                && selectedTargetUnavailable == other.selectedTargetUnavailable
                && Objects.equals(selectedTarget, other.selectedTarget);
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
        buffer.writeVarLong(payload.sequence);
        buffer.writeVarLong(payload.geometryVersion);
        buffer.writeVarLong(payload.observedServerTick);
        buffer.writeBoolean(payload.linked);
        buffer.writeBoolean(payload.selectedTargetUnavailable);
        writeTargetRef(buffer, payload.selectedTarget);
        buffer.writeBoolean(payload.includesGeometry);
        buffer.writeVarInt(payload.partIndex);
        buffer.writeVarInt(payload.partCount);
        buffer.writeVarInt(payload.visualProfiles.size());
        for (RadarVisualProfile profile : payload.visualProfiles) writeProfile(buffer, profile);
        buffer.writeVarInt(payload.visualPhases.size());
        for (MonitorVisualPhase phase : payload.visualPhases) writePhase(buffer, phase);
        buffer.writeVarInt(payload.targets.size());
        for (MonitorTargetData target : payload.targets) writeTargetData(buffer, target);
    }

    private static MonitorSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        UUID displayId = buffer.readUUID();
        BlockPos root = buffer.readBlockPos();
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID loadId = buffer.readUUID();
        long sequence = buffer.readVarLong();
        long geometryVersion = buffer.readVarLong();
        long observedServerTick = buffer.readVarLong();
        boolean linked = buffer.readBoolean();
        boolean selectionUnavailable = buffer.readBoolean();
        TargetRef selected = readTargetRef(buffer);
        boolean includesGeometry = buffer.readBoolean();
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        int profileCount = readCount(buffer, PROFILES_PER_PART);
        ArrayList<RadarVisualProfile> profiles = new ArrayList<>(profileCount);
        for (int i = 0; i < profileCount; i++) profiles.add(readProfile(buffer));
        int phaseCount = readCount(buffer, PHASES_PER_PART);
        ArrayList<MonitorVisualPhase> phases = new ArrayList<>(phaseCount);
        for (int i = 0; i < phaseCount; i++) phases.add(readPhase(buffer));
        int targetCount = readCount(buffer, TARGETS_PER_PART);
        ArrayList<MonitorTargetData> targets = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) targets.add(readTargetData(buffer));
        return new MonitorSnapshotPayload(displayId, root, dimension, loadId, sequence, geometryVersion,
                observedServerTick, linked, selectionUnavailable, selected, includesGeometry, index, count,
                targets, profiles, phases);
    }

    private static void writePhase(RegistryFriendlyByteBuf buffer, MonitorVisualPhase phase) {
        buffer.writeBlockPos(phase.radarPosition());
        buffer.writeVarInt(phase.activeSectorIndices().size());
        for (int sectorIndex : phase.activeSectorIndices()) buffer.writeVarInt(sectorIndex);
        buffer.writeVarLong(phase.windowStartTick());
        buffer.writeVarLong(phase.windowEndTick());
    }

    private static MonitorVisualPhase readPhase(RegistryFriendlyByteBuf buffer) {
        BlockPos radarPosition = buffer.readBlockPos();
        int count = readCount(buffer, RadarVisualProfile.MAX_SECTOR_COUNT);
        if (count == 0) throw new IllegalArgumentException("Monitor phase has no active sectors");
        int[] indices = new int[count];
        long seen = 0L;
        for (int i = 0; i < count; i++) {
            int index = buffer.readVarInt();
            if (index < 0 || index >= RadarVisualProfile.MAX_SECTOR_COUNT) {
                throw new IllegalArgumentException("Monitor phase sector index exceeds bounds");
            }
            long bit = 1L << index;
            if ((seen & bit) != 0L) throw new IllegalArgumentException("Duplicate monitor phase sector index");
            seen |= bit;
            indices[i] = index;
        }
        ArrayList<Integer> active = new ArrayList<>(count);
        for (int index : indices) active.add(index);
        return new MonitorVisualPhase(radarPosition, active,
                buffer.readVarLong(), buffer.readVarLong());
    }

    private static void writeTargetData(RegistryFriendlyByteBuf buffer, MonitorTargetData target) {
        writeTargetRef(buffer, target.target());
        buffer.writeBoolean(target.observing());
        buffer.writeBoolean(target.hasPosition());
        if (target.hasPosition()) {
            buffer.writeDouble(target.x());
            buffer.writeDouble(target.y());
            buffer.writeDouble(target.z());
            buffer.writeUtf(target.entityTypeId(), 256);
        }
        buffer.writeVarLong(target.freezeTick() + 1L);
        buffer.writeVarLong(target.fadeDurationTicks());
    }

    private static MonitorTargetData readTargetData(RegistryFriendlyByteBuf buffer) {
        TargetRef ref = readTargetRef(buffer);
        if (ref == null) throw new IllegalArgumentException("Monitor target record cannot be null");
        boolean observing = buffer.readBoolean();
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
        long freezeTick = buffer.readVarLong() - 1L;
        long fadeTicks = buffer.readVarLong();
        return new MonitorTargetData(ref, observing, hasPosition, x, y, z, typeId, freezeTick, fadeTicks);
    }

    /** Универсальный формат примитивов; номер порядка одинаков для всех наблюдателей. */
    private static void writeProfile(RegistryFriendlyByteBuf buffer, RadarVisualProfile profile) {
        buffer.writeResourceLocation(profile.dimension());
        buffer.writeBlockPos(profile.radarPosition());
        // Явный ограниченный ID сохраняет wire-контракт независимым от enum.ordinal().
        buffer.writeVarInt(profile.animation().wireId());
        buffer.writeVarInt(profile.sectorOrderVariant());
        buffer.writeVarInt(profile.inactiveSectorFadeTicks());
        buffer.writeDouble(profile.centerX());
        buffer.writeDouble(profile.centerY());
        buffer.writeDouble(profile.centerZ());
        RadarVisualGeometry.Coverage geometry = profile.coverage();
        writeSegments(buffer, geometry.outline());
        writeSegments(buffer, geometry.separators());
        buffer.writeVarInt(geometry.sectors().size());
        for (RadarVisualGeometry.Sector sector : geometry.sectors()) {
            buffer.writeVarInt(sector.triangles().size());
            for (RadarVisualGeometry.Triangle triangle : sector.triangles()) {
                writePoint(buffer, triangle.a());
                writePoint(buffer, triangle.b());
                writePoint(buffer, triangle.c());
            }
            writeSegments(buffer, sector.boundary());
        }
    }

    /** Проверяет размеры до выделения списков и до передачи геометрии клиентскому рендеру. */
    private static RadarVisualProfile readProfile(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        BlockPos position = buffer.readBlockPos();
        RadarVisualAnimation animation = RadarVisualAnimation.fromWireId(buffer.readVarInt());
        int orderVariant = buffer.readVarInt();
        int inactiveSectorFadeTicks = buffer.readVarInt();
        if (!RadarSectorOrder.validVariant(orderVariant)) {
            throw new IllegalArgumentException("Invalid radar sector order variant");
        }
        double centerX = buffer.readDouble();
        double centerY = buffer.readDouble();
        double centerZ = buffer.readDouble();
        List<RadarVisualGeometry.Segment> outline = readSegments(buffer);
        List<RadarVisualGeometry.Segment> separators = readSegments(buffer);
        int sectorCount = readCount(buffer, RadarVisualProfile.MAX_SECTOR_COUNT);
        if (sectorCount < RadarVisualProfile.MIN_SECTOR_COUNT) {
            throw new IllegalArgumentException("Empty radar visual coverage");
        }
        ArrayList<RadarVisualGeometry.Sector> sectors = new ArrayList<>(sectorCount);
        int triangleCount = 0;
        int lineCount = outline.size() + separators.size();
        for (int i = 0; i < sectorCount; i++) {
            int count = readCount(buffer, RadarVisualGeometry.MAX_TRIANGLES - triangleCount);
            ArrayList<RadarVisualGeometry.Triangle> triangles = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                triangles.add(new RadarVisualGeometry.Triangle(
                        readPoint(buffer), readPoint(buffer), readPoint(buffer)));
            }
            triangleCount += count;
            List<RadarVisualGeometry.Segment> boundary = readSegments(buffer);
            lineCount += boundary.size();
            if (lineCount > RadarVisualGeometry.MAX_LINES) {
                throw new IllegalArgumentException("Radar visual geometry has too many lines");
            }
            sectors.add(new RadarVisualGeometry.Sector(i, triangles, boundary));
        }
        // Общие инварианты пустых частей и покрытия проверяет Coverage также при создании на сервере.
        return new RadarVisualProfile(dimension, position, centerX, centerY, centerZ,
                new RadarVisualGeometry.Coverage(outline, separators, sectors), animation, orderVariant,
                inactiveSectorFadeTicks);
    }

    private static void writePoint(RegistryFriendlyByteBuf buffer, RadarVisualGeometry.Point point) {
        buffer.writeDouble(point.x());
        buffer.writeDouble(point.z());
    }

    private static RadarVisualGeometry.Point readPoint(RegistryFriendlyByteBuf buffer) {
        return new RadarVisualGeometry.Point(buffer.readDouble(), buffer.readDouble());
    }

    private static void writeSegments(RegistryFriendlyByteBuf buffer,
                                      List<RadarVisualGeometry.Segment> segments) {
        buffer.writeVarInt(segments.size());
        for (RadarVisualGeometry.Segment segment : segments) {
            writePoint(buffer, segment.start());
            writePoint(buffer, segment.end());
        }
    }

    private static List<RadarVisualGeometry.Segment> readSegments(RegistryFriendlyByteBuf buffer) {
        int count = readCount(buffer, RadarVisualGeometry.MAX_LINES);
        ArrayList<RadarVisualGeometry.Segment> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segments.add(new RadarVisualGeometry.Segment(readPoint(buffer), readPoint(buffer)));
        }
        return List.copyOf(segments);
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Monitor snapshot list exceeds packet bounds");
        }
        return count;
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
