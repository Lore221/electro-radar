package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;
import com.electroradar.radar.network.TargetRef;

/**
 * Клиентский кэш последнего снимка и сборщик его сетевых частей.
 *
 * <p>Кэш не хранит историю: каждая новая полная версия заменяет прежнюю, исчезнувшие цели
 * удаляются сразу. Для помнимой цели допускается только последняя подтверждённая клиентом
 * позиция; серверные координаты помнимой сущности никогда не используются. При смене уровня,
 * корня или UUID загрузки данные старого экрана не подходят новому.</p>
 */
public final class MonitorClientState {
    private static final Map<MonitorDisplayKey, View> VIEWS = new HashMap<>();
    private static final Map<PartKey, PendingParts> PENDING = new HashMap<>();
    private static final Map<MonitorDisplayKey, SnapshotVersion> PENDING_VERSIONS = new HashMap<>();
    private static ClientLevel currentLevel;

    private MonitorClientState() {
    }

    /** Клиентская запись с точкой из прошлого подтверждённого скана для затухающей метки. */
    public record DisplayTarget(TargetRef target, boolean detectedThisWindow, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                boolean rememberedPoint, long rememberedSinceGameTime) {
        /** Устаревшая точка затухает по клиентскому времени, но не возвращается к полной яркости. */
        public float rememberedOpacity(long gameTime) {
            if (!rememberedPoint) return 1.0F;
            long age = Math.max(0L, gameTime - rememberedSinceGameTime);
            return Math.max(0.12F, 1.0F - age / 40.0F);
        }
    }

    /** Снимок для рендереров и GUI с уже безопасно сохранёнными точками помнимых целей. */
    public record View(MonitorDisplayKey key, MonitorSnapshotData snapshot,
                       List<DisplayTarget> targets) {
        public View {
            targets = List.copyOf(targets);
        }
    }

    /** Собирает payload части и применяет снимок только после получения всей серии. */
    public static void accept(MonitorSnapshotPayload part) {
        ClientLevel level = currentLevel();
        if (level == null || !level.dimension().location().equals(part.dimension())
                || !matchesLoadedRoot(level, part.rootPosition(), part.displayId(), part.loadId())) {
            return;
        }
        MonitorDisplayKey key = new MonitorDisplayKey(part.dimension(), part.rootPosition(),
                part.displayId(), part.loadId());
        View current = VIEWS.get(key);
        if (current != null && compare(part.structureVersion(), part.publishedEpoch(), part.revision(),
                current.snapshot().structureVersion(), current.snapshot().publishedEpoch(),
                current.snapshot().revision()) < 0) {
            return;
        }
        SnapshotVersion incomingVersion = new SnapshotVersion(
                part.structureVersion(), part.publishedEpoch(), part.revision());
        SnapshotVersion pendingVersion = PENDING_VERSIONS.get(key);
        if (pendingVersion != null && incomingVersion.compareTo(pendingVersion) < 0) return;
        if (pendingVersion == null || incomingVersion.compareTo(pendingVersion) > 0) {
            PENDING.keySet().removeIf(existing -> existing.display.equals(key));
            PENDING_VERSIONS.put(key, incomingVersion);
        }
        PartKey partKey = new PartKey(key, part.structureVersion(), part.publishedEpoch(),
                part.revision(), part.partCount());
        PendingParts pending = PENDING.computeIfAbsent(partKey,
                ignored -> new PendingParts(part.partCount(), level.getGameTime()));
        if (pending.parts[part.partIndex()] == null) {
            pending.parts[part.partIndex()] = part.targets();
            pending.received++;
        }
        pending.metadata = part;
        if (pending.received != pending.parts.length) return;

        ArrayList<MonitorTargetData> allTargets = new ArrayList<>();
        for (List<MonitorTargetData> targets : pending.parts) allTargets.addAll(targets);
        MonitorSnapshotData snapshot;
        try {
            snapshot = pending.metadata.asSnapshot(allTargets);
        } catch (IllegalArgumentException malformed) {
            PENDING.remove(partKey);
            return;
        }
        PENDING.remove(partKey);
        apply(key, snapshot);
        PENDING_VERSIONS.remove(key);
    }

    /** Возвращает актуальный клиентский вид, если корень всё ещё загружен и совпадает по идентичности. */
    public static View get(MonitorDisplayKey key) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = currentLevel();
        if (level == null || !level.dimension().location().equals(key.dimension())
                || !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId())) {
            VIEWS.remove(key);
            if (minecraft.screen instanceof RadarMonitorScreen screen && screen.isFor(key)) {
                minecraft.setScreen(null);
            }
            return null;
        }
        return VIEWS.get(key);
    }

    /** Очищает клиентское состояние при смене мира или физической сборки. */
    public static void clear() {
        VIEWS.clear();
        PENDING.clear();
        PENDING_VERSIONS.clear();
        currentLevel = null;
    }

    /** Удаляет состояния, чей корневой блок оказался загружен как другой блок или другая загрузка. */
    public static void pruneLoadedRoots() {
        ClientLevel level = currentLevel();
        if (level == null) return;
        VIEWS.keySet().removeIf(key -> level.hasChunkAt(key.rootPosition())
                && !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId()));
        PENDING.entrySet().removeIf(entry -> level.getGameTime() - entry.getValue().createdAtGameTime > 200L
                || level.hasChunkAt(entry.getKey().display.rootPosition())
                && !matchesLoadedRoot(level, entry.getKey().display.rootPosition(),
                entry.getKey().display.displayId(), entry.getKey().display.loadId()));
        PENDING_VERSIONS.keySet().removeIf(key -> PENDING.keySet().stream()
                .noneMatch(part -> part.display.equals(key)));
    }

    private static void apply(MonitorDisplayKey key, MonitorSnapshotData snapshot) {
        View previous = VIEWS.get(key);
        Map<TargetRef, MonitorTargetData> previousTargets = new HashMap<>();
        Map<TargetRef, DisplayTarget> previousDisplayTargets = new HashMap<>();
        if (previous != null) {
            for (DisplayTarget target : previous.targets()) {
                previousDisplayTargets.put(target.target(), target);
                if (target.hasPosition()) {
                    previousTargets.put(target.target(), new MonitorTargetData(target.target(), true, true,
                            target.x(), target.y(), target.z(), target.entityTypeId()));
                }
            }
        }
        ArrayList<DisplayTarget> displayTargets = new ArrayList<>(snapshot.targets().size());
        ClientLevel level = currentLevel();
        long receivedAt = level == null ? 0L : level.getGameTime();
        for (MonitorTargetData target : snapshot.targets()) {
            if (target.hasPosition()) {
                displayTargets.add(new DisplayTarget(target.target(), target.detectedThisWindow(), true,
                        target.x(), target.y(), target.z(), target.entityTypeId(), false, 0L));
                continue;
            }
            MonitorTargetData old = previousTargets.get(target.target());
            if (old != null && old.hasPosition()) {
                DisplayTarget oldDisplay = previousDisplayTargets.get(target.target());
                long rememberedSince = oldDisplay != null && oldDisplay.rememberedPoint()
                        ? oldDisplay.rememberedSinceGameTime()
                        : receivedAt;
                displayTargets.add(new DisplayTarget(target.target(), false, true,
                        old.x(), old.y(), old.z(), old.entityTypeId(), true, rememberedSince));
            } else {
                displayTargets.add(new DisplayTarget(target.target(), target.detectedThisWindow(), false,
                        0, 0, 0, "", false, 0L));
            }
        }
        VIEWS.put(key, new View(key, snapshot, displayTargets));
    }

    private static int compare(long structureA, long epochA, long revisionA,
                               long structureB, long epochB, long revisionB) {
        int structure = Long.compare(structureA, structureB);
        if (structure != 0) return structure;
        int epoch = Long.compare(epochA, epochB);
        return epoch != 0 ? epoch : Long.compare(revisionA, revisionB);
    }

    private static ClientLevel currentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != currentLevel) {
            VIEWS.clear();
            PENDING.clear();
            PENDING_VERSIONS.clear();
            currentLevel = level;
        }
        return level;
    }

    static boolean matchesLoadedRoot(ClientLevel level, net.minecraft.core.BlockPos position,
                                     UUID displayId, UUID loadId) {
        if (!level.hasChunkAt(position)) return false;
        BlockEntity blockEntity = level.getBlockEntity(position);
        return blockEntity instanceof RadarDisplayBlockEntity root
                && root.isRoot()
                && displayId.equals(root.displayId())
                && loadId.equals(root.loadId());
    }

    private record PartKey(MonitorDisplayKey display, long structureVersion, long epoch,
                           long revision, int partCount) {
    }

    private record SnapshotVersion(long structureVersion, long epoch, long revision)
            implements Comparable<SnapshotVersion> {
        @Override
        public int compareTo(SnapshotVersion other) {
            return compare(structureVersion, epoch, revision,
                    other.structureVersion, other.epoch, other.revision);
        }
    }

    private static final class PendingParts {
        private final List<MonitorTargetData>[] parts;
        private final long createdAtGameTime;
        private int received;
        private MonitorSnapshotPayload metadata;

        @SuppressWarnings("unchecked")
        private PendingParts(int count, long createdAtGameTime) {
            if (count < 1 || count > 65_536) throw new IllegalArgumentException("Invalid part count");
            parts = (List<MonitorTargetData>[]) new List<?>[count];
            this.createdAtGameTime = createdAtGameTime;
        }
    }
}
