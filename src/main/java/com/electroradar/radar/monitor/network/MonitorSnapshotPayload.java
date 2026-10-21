package com.electroradar.radar.monitor.network;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualAnimation;

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
        long sequence,
        long structureVersion,
        long publishedEpoch,
        long revision,
        boolean linked,
        boolean selectedTargetUnavailable,
        TargetRef selectedTarget,
        int partIndex,
        int partCount,
        List<MonitorTargetData> targets,
        List<RadarVisualProfile> visualProfiles,
        long visualWindowPeriodTicks,
        long visualWindowRemainingTicks
) implements CustomPacketPayload {
    public static final int TARGETS_PER_PART = 192;
    /** Геометрия разбивается между частями: одна большая сеть не создаёт гигантский пакет. */
    public static final int VISUAL_PROFILES_PER_SNAPSHOT = 8;
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
        visualProfiles = List.copyOf(visualProfiles);
        if (sequence < 0 || structureVersion < 0 || publishedEpoch < 0 || revision < 0
                || partCount < 1 || partCount > 65_536
                || partIndex < 0 || partIndex >= partCount || targets.size() > TARGETS_PER_PART
                || visualProfiles.size() > VISUAL_PROFILES_PER_SNAPSHOT
                || visualWindowPeriodTicks < 0 || visualWindowRemainingTicks < 0
                || visualWindowRemainingTicks > visualWindowPeriodTicks
                || !visualProfiles.isEmpty() && (!linked
                || partIndex == 0 && visualWindowPeriodTicks == 0)
                || partIndex != 0 && (visualWindowPeriodTicks != 0
                || visualWindowRemainingTicks != 0)) {
            throw new IllegalArgumentException("Invalid monitor snapshot part bounds");
        }
        if (visualProfiles.stream().anyMatch(profile -> !profile.dimension().equals(dimension))) {
            throw new IllegalArgumentException("Visual profile belongs to another monitor dimension");
        }
        Set<RadarVisualProfile.Key> profileKeys = new HashSet<>();
        for (RadarVisualProfile profile : visualProfiles) {
            if (!profileKeys.add(profile.key())) {
                throw new IllegalArgumentException("Duplicate radar visual profile in monitor part");
            }
        }
    }

    /** Разбивает готовый снимок на части с общей версией и неизменным порядком целей. */
    public static List<MonitorSnapshotPayload> split(MonitorSnapshotData snapshot) {
        int targetParts = (snapshot.targets().size() + TARGETS_PER_PART - 1) / TARGETS_PER_PART;
        int profileParts = (snapshot.visualProfiles().size() + VISUAL_PROFILES_PER_SNAPSHOT - 1)
                / VISUAL_PROFILES_PER_SNAPSHOT;
        int count = Math.max(1, Math.max(targetParts, profileParts));
        java.util.ArrayList<MonitorSnapshotPayload> parts = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int from = Math.min(snapshot.targets().size(), index * TARGETS_PER_PART);
            int to = Math.min(snapshot.targets().size(), from + TARGETS_PER_PART);
            int profileFrom = Math.min(snapshot.visualProfiles().size(),
                    index * VISUAL_PROFILES_PER_SNAPSHOT);
            int profileTo = Math.min(snapshot.visualProfiles().size(),
                    profileFrom + VISUAL_PROFILES_PER_SNAPSHOT);
            parts.add(new MonitorSnapshotPayload(snapshot.displayId(), snapshot.rootPosition(),
                    snapshot.dimension(), snapshot.loadId(), snapshot.sequence(), snapshot.structureVersion(),
                    snapshot.publishedEpoch(), snapshot.revision(), snapshot.linked(),
                    snapshot.selectedTargetUnavailable(), snapshot.selectedTarget(), index, count,
                    snapshot.targets().subList(from, to),
                    snapshot.visualProfiles().subList(profileFrom, profileTo),
                    index == 0 ? snapshot.visualWindowPeriodTicks() : 0L,
                    index == 0 ? snapshot.visualWindowRemainingTicks() : 0L));
        }
        return List.copyOf(parts);
    }

    /** Собирает завершённую на клиенте серию частей обратно в неизменяемую запись. */
    public MonitorSnapshotData asSnapshot(List<MonitorTargetData> allTargets,
                                          List<RadarVisualProfile> profiles,
                                          long windowPeriodTicks, long windowRemainingTicks) {
        return new MonitorSnapshotData(displayId, rootPosition, dimension, loadId,
                sequence, structureVersion, publishedEpoch, revision, linked,
                selectedTargetUnavailable, selectedTarget, allTargets,
                profiles, windowPeriodTicks, windowRemainingTicks);
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
        buffer.writeVarLong(payload.structureVersion);
        buffer.writeVarLong(payload.publishedEpoch);
        buffer.writeVarLong(payload.revision);
        buffer.writeBoolean(payload.linked);
        buffer.writeBoolean(payload.selectedTargetUnavailable);
        writeTargetRef(buffer, payload.selectedTarget);
        buffer.writeVarInt(payload.partIndex);
        buffer.writeVarInt(payload.partCount);
        buffer.writeVarInt(payload.visualProfiles.size());
        for (RadarVisualProfile profile : payload.visualProfiles) writeProfile(buffer, profile);
        if (payload.partIndex == 0) {
            buffer.writeVarLong(payload.visualWindowPeriodTicks);
            buffer.writeVarLong(payload.visualWindowRemainingTicks);
        }
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
        long sequence = buffer.readVarLong();
        long structureVersion = buffer.readVarLong();
        long epoch = buffer.readVarLong();
        long revision = buffer.readVarLong();
        boolean linked = buffer.readBoolean();
        boolean selectionUnavailable = buffer.readBoolean();
        TargetRef selected = readTargetRef(buffer);
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        int profileCount = buffer.readVarInt();
        if (profileCount < 0 || profileCount > VISUAL_PROFILES_PER_SNAPSHOT) {
            throw new IllegalArgumentException("Monitor snapshot has too many visual profiles in part");
        }
        java.util.ArrayList<RadarVisualProfile> decodedProfiles = new java.util.ArrayList<>(profileCount);
        for (int i = 0; i < profileCount; i++) decodedProfiles.add(readProfile(buffer));
        List<RadarVisualProfile> profiles = List.copyOf(decodedProfiles);
        long windowPeriod = 0L;
        long windowRemaining = 0L;
        if (index == 0) {
            windowPeriod = buffer.readVarLong();
            windowRemaining = buffer.readVarLong();
        }
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
                sequence, structureVersion, epoch, revision, linked, selectionUnavailable,
                selected, index, count, targets, profiles, windowPeriod, windowRemaining);
    }

    /** Универсальный формат примитивов: новый тип радара не меняет кодек или монитор. */
    private static void writeProfile(RegistryFriendlyByteBuf buffer, RadarVisualProfile profile) {
        buffer.writeResourceLocation(profile.dimension());
        buffer.writeBlockPos(profile.radarPosition());
        // Явный ограниченный ID сохраняет wire-контракт независимым от enum.ordinal().
        buffer.writeVarInt(profile.animation().wireId());
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
        double centerX = buffer.readDouble();
        double centerY = buffer.readDouble();
        double centerZ = buffer.readDouble();
        List<RadarVisualGeometry.Segment> outline = readSegments(buffer);
        List<RadarVisualGeometry.Segment> separators = readSegments(buffer);
        int sectorCount = readCount(buffer, RadarVisualProfile.MAX_SECTOR_COUNT);
        if (sectorCount < RadarVisualProfile.MIN_SECTOR_COUNT) {
            throw new IllegalArgumentException("Empty radar visual coverage");
        }
        java.util.ArrayList<RadarVisualGeometry.Sector> sectors = new java.util.ArrayList<>(sectorCount);
        int triangleCount = 0;
        int lineCount = outline.size() + separators.size();
        for (int i = 0; i < sectorCount; i++) {
            int count = readCount(buffer, RadarVisualGeometry.MAX_TRIANGLES - triangleCount);
            java.util.ArrayList<RadarVisualGeometry.Triangle> triangles = new java.util.ArrayList<>(count);
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
                new RadarVisualGeometry.Coverage(outline, separators, sectors), animation);
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
        java.util.ArrayList<RadarVisualGeometry.Segment> segments = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segments.add(new RadarVisualGeometry.Segment(readPoint(buffer), readPoint(buffer)));
        }
        return List.copyOf(segments);
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Radar visual geometry exceeds packet bounds");
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
