package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.network.MonitorDeltaPayload;
import com.electroradar.radar.monitor.network.MonitorOpenPayload;
import com.electroradar.radar.monitor.network.MonitorResyncRequestPayload;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Клиентский baseline/delta cache; незавершённые серии имеют явные лимиты и срок жизни. */
public final class MonitorClientState {
    private static final int MAX_PENDING_BASELINES = 8;
    private static final int MAX_PENDING_PARTS = 4_096;
    private static final int MAX_PENDING_DELTA_SERIES = 8;
    private static final int MAX_QUEUED_DELTAS_PER_DISPLAY = 8;
    private static final long PENDING_TIMEOUT_TICKS = 200L;
    private static final long RESYNC_COOLDOWN_TICKS = 20L;
    private static final Map<MonitorDisplayKey, View> VIEWS = new HashMap<>();
    private static final Map<BaselinePartKey, PendingBaseline> PENDING_BASELINES = new HashMap<>();
    private static final Map<MonitorDisplayKey, Long> PENDING_SEQUENCES = new HashMap<>();
    private static final Map<DeltaPartKey, PendingDelta> PENDING_DELTA_SERIES = new HashMap<>();
    private static final Map<MonitorDisplayKey, List<CompleteDelta>> QUEUED_DELTAS = new HashMap<>();
    private static final Map<MonitorDisplayKey, Long> LAST_RESYNC_REQUEST = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long clientTickTime;
    private static double lastRenderTime;

    private MonitorClientState() { }

    /** Клиентский target сохраняет только выданный сервером последний допустимый sample. */
    public record DisplayTarget(TargetRef target, boolean observing, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                boolean rememberedPoint, double rememberedFadeStartClientTick,
                                long rememberedFadeDurationTicks) {
        public float rememberedOpacity(double clientRenderTime) {
            if (!rememberedPoint) return 1.0F;
            if (rememberedFadeDurationTicks <= 0L) return 0.0F;
            double age = Math.max(0.0D, clientRenderTime - rememberedFadeStartClientTick);
            return (float) Math.clamp(1.0D - age / rememberedFadeDurationTicks, 0.0D, 1.0D);
        }

        public boolean detectedThisWindow() { return observing; }
    }

    public record View(MonitorDisplayKey key, MonitorSnapshotData snapshot, List<DisplayTarget> targets,
                       List<MonitorSectorAnimationState.ProfileFrame> visualFrames) {
        public View {
            targets = List.copyOf(targets);
            visualFrames = List.copyOf(visualFrames);
        }

        public float targetOpacity(DisplayTarget target, double renderTime) {
            // Право наблюдения уже проверено сервером по полному hitbox. Центр отметки
            // может лежать в соседнем секторе и не должен запускать дополнительное затухание.
            return target.rememberedPoint() ? target.rememberedOpacity(renderTime) : 1.0F;
        }
    }

    /** Принимает baseline целиком; sequence и viewGeneration проверяются до изменения состояния. */
    public static void accept(MonitorSnapshotPayload part) {
        ClientLevel level = currentLevel();
        if (!validRoot(level, part.dimension(), part.rootPosition(), part.displayId(), part.loadId())) return;
        MonitorDisplayKey key = key(part.dimension(), part.rootPosition(), part.displayId(), part.loadId());
        if (part.partCount() > MAX_PENDING_PARTS) {
            requestResync(key);
            return;
        }
        View current = VIEWS.get(key);
        if (current != null && (part.viewGeneration() < current.snapshot().viewGeneration()
                || part.sequence() < current.snapshot().sequence())) return;
        Long pendingSequence = PENDING_SEQUENCES.get(key);
        if (pendingSequence != null && part.sequence() < pendingSequence) return;
        if (pendingSequence == null || part.sequence() > pendingSequence) {
            PENDING_BASELINES.keySet().removeIf(existing -> existing.display().equals(key));
            PENDING_SEQUENCES.put(key, part.sequence());
        }
        BaselinePartKey partKey = new BaselinePartKey(key, part.sequence(), part.viewGeneration(), part.partCount());
        PendingBaseline pending = PENDING_BASELINES.get(partKey);
        if (pending == null) {
            if (pendingBaselineCount() >= MAX_PENDING_BASELINES) {
                requestResync(key);
                return;
            }
            pending = new PendingBaseline(part.partCount(), clientTickTime);
            PENDING_BASELINES.put(partKey, pending);
        }
        if (pending.metadata != null && !sameBaselineHeader(pending.metadata, part)) {
            PENDING_BASELINES.remove(partKey);
            PENDING_SEQUENCES.remove(key);
            requestResync(key);
            return;
        }
        if (pending.targets[part.partIndex()] == null) {
            pending.targets[part.partIndex()] = part.targets();
            pending.profiles[part.partIndex()] = part.visualProfiles();
            pending.phases[part.partIndex()] = part.visualPhases();
            pending.received++;
        } else if (!pending.targets[part.partIndex()].equals(part.targets())
                || !pending.profiles[part.partIndex()].equals(part.visualProfiles())
                || !pending.phases[part.partIndex()].equals(part.visualPhases())) {
            PENDING_BASELINES.remove(partKey);
            PENDING_SEQUENCES.remove(key);
            requestResync(key);
            return;
        }
        if (part.partIndex() == 0) {
            pending.metadata = part;
        } else if (pending.metadata == null) {
            pending.metadata = part;
        }
        if (pending.received != pending.targets.length || pending.metadata == null) return;

        ArrayList<MonitorTargetData> targets = new ArrayList<>();
        ArrayList<RadarVisualProfile> profiles = new ArrayList<>();
        ArrayList<MonitorVisualPhase> phases = new ArrayList<>();
        for (List<MonitorTargetData> values : pending.targets) targets.addAll(values);
        for (List<RadarVisualProfile> values : pending.profiles) profiles.addAll(values);
        for (List<MonitorVisualPhase> values : pending.phases) phases.addAll(values);
        MonitorSnapshotData snapshot;
        try {
            snapshot = pending.metadata.asSnapshot(targets, profiles, phases);
        } catch (IllegalArgumentException malformed) {
            PENDING_BASELINES.remove(partKey);
            PENDING_SEQUENCES.remove(key);
            requestResync(key);
            return;
        }
        PENDING_BASELINES.remove(partKey);
        PENDING_SEQUENCES.remove(key);
        apply(key, snapshot);
        drainQueuedDeltas(key);
    }

    /** Собирает multipart delta и применяет только целую абсолютную версию. */
    public static void accept(MonitorDeltaPayload part) {
        ClientLevel level = currentLevel();
        if (!validRoot(level, part.dimension(), part.rootPosition(), part.displayId(), part.loadId())) return;
        MonitorDisplayKey key = key(part.dimension(), part.rootPosition(), part.displayId(), part.loadId());
        if (part.partCount() > MAX_PENDING_PARTS) {
            requestResync(key);
            return;
        }
        View current = VIEWS.get(key);
        if (current != null) {
            long generation = current.snapshot().viewGeneration();
            if (part.viewGeneration() < generation || part.toSequence() <= current.snapshot().sequence()) return;
        }
        DeltaPartKey partKey = new DeltaPartKey(key, part.viewGeneration(), part.fromSequence(),
                part.toSequence(), part.partCount());
        PendingDelta pending = PENDING_DELTA_SERIES.get(partKey);
        if (pending == null) {
            if (PENDING_DELTA_SERIES.size() >= MAX_PENDING_DELTA_SERIES) {
                requestResync(key);
                return;
            }
            pending = new PendingDelta(part.partCount(), clientTickTime);
            PENDING_DELTA_SERIES.put(partKey, pending);
        }
        if (pending.metadata != null && !sameDeltaHeader(pending.metadata, part)) {
            PENDING_DELTA_SERIES.remove(partKey);
            requestResync(key);
            return;
        }
        if (pending.upserts[part.partIndex()] == null) {
            pending.upserts[part.partIndex()] = part.upserts();
            pending.removes[part.partIndex()] = part.removes();
            pending.phases[part.partIndex()] = part.visualPhases();
            pending.received++;
        } else if (!pending.upserts[part.partIndex()].equals(part.upserts())
                || !pending.removes[part.partIndex()].equals(part.removes())
                || !pending.phases[part.partIndex()].equals(part.visualPhases())) {
            PENDING_DELTA_SERIES.remove(partKey);
            requestResync(key);
            return;
        }
        if (part.partIndex() == 0) {
            pending.metadata = part;
            pending.hasHeader = true;
        } else if (pending.metadata == null) {
            pending.metadata = part;
        }
        if (pending.received != pending.upserts.length || !pending.hasHeader) return;
        ArrayList<MonitorTargetData> upserts = new ArrayList<>();
        ArrayList<TargetRef> removes = new ArrayList<>();
        ArrayList<MonitorVisualPhase> phases = new ArrayList<>();
        for (List<MonitorTargetData> values : pending.upserts) upserts.addAll(values);
        for (List<TargetRef> values : pending.removes) removes.addAll(values);
        for (List<MonitorVisualPhase> values : pending.phases) phases.addAll(values);
        CompleteDelta complete = new CompleteDelta(pending.metadata, upserts, removes, phases);
        PENDING_DELTA_SERIES.remove(partKey);
        if (PENDING_SEQUENCES.containsKey(key) || !VIEWS.containsKey(key)) {
            queueDelta(key, complete);
            requestResync(key);
        } else {
            applyOrQueue(key, complete);
        }
    }

    private static void applyOrQueue(MonitorDisplayKey key, CompleteDelta delta) {
        View current = VIEWS.get(key);
        if (current == null) {
            queueDelta(key, delta);
            requestResync(key);
            return;
        }
        MonitorDeltaPayload header = delta.header();
        long currentGeneration = current.snapshot().viewGeneration();
        if (header.viewGeneration() < currentGeneration || header.toSequence() <= current.snapshot().sequence()) return;
        if (header.viewGeneration() > currentGeneration
                || header.fromSequence() > current.snapshot().sequence()) {
            queueDelta(key, delta);
            requestResync(key);
            return;
        }
        Map<TargetRef, MonitorTargetData> targets = new HashMap<>();
        for (MonitorTargetData target : current.snapshot().targets()) targets.put(target.target(), target);
        for (TargetRef remove : delta.removes()) targets.remove(remove);
        for (MonitorTargetData upsert : delta.upserts()) targets.put(upsert.target(), upsert);
        List<MonitorTargetData> ordered = targets.values().stream().sorted(Comparator
                .comparing((MonitorTargetData value) -> value.target().dimension().location().toString())
                .thenComparing(value -> value.target().sourceKind().name())
                .thenComparing(value -> value.target().id().toString())).toList();
        try {
            MonitorSnapshotData snapshot = new MonitorSnapshotData(header.displayId(), header.rootPosition(),
                    header.dimension(), header.loadId(), header.toSequence(), header.structureVersion(),
                    header.publishedEpoch(), header.revision(), header.linked(),
                    header.selectedTargetUnavailable(), header.selectedTarget(), ordered,
                    current.snapshot().visualProfiles(), header.measurementRevision(), header.viewGeneration(),
                    header.observedServerTick(), delta.visualPhases());
            apply(key, snapshot);
        } catch (IllegalArgumentException malformed) {
            requestResync(key);
        }
    }

    private static void queueDelta(MonitorDisplayKey key, CompleteDelta delta) {
        List<CompleteDelta> queued = QUEUED_DELTAS.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (queued.size() >= MAX_QUEUED_DELTAS_PER_DISPLAY) {
            queued.clear();
            QUEUED_DELTAS.remove(key);
            requestResync(key);
            return;
        }
        if (queued.stream().noneMatch(existing -> existing.header().fromSequence() == delta.header().fromSequence()
                && existing.header().toSequence() == delta.header().toSequence()
                && existing.header().viewGeneration() == delta.header().viewGeneration())) queued.add(delta);
        if (QUEUED_DELTAS.size() > MAX_PENDING_BASELINES * 4) {
            QUEUED_DELTAS.remove(key);
            requestResync(key);
        }
    }

    private static void drainQueuedDeltas(MonitorDisplayKey key) {
        List<CompleteDelta> queued = QUEUED_DELTAS.remove(key);
        if (queued == null) return;
        queued.sort(Comparator.comparingLong(delta -> delta.header().toSequence()));
        for (CompleteDelta delta : queued) applyOrQueue(key, delta);
    }

    private static void requestResync(MonitorDisplayKey key) {
        if (!matchesLoadedRoot(currentLevel(), key.rootPosition(), key.displayId(), key.loadId())) return;
        long last = LAST_RESYNC_REQUEST.getOrDefault(key, Long.MIN_VALUE / 2);
        if (clientTickTime - last < RESYNC_COOLDOWN_TICKS) return;
        if (!LAST_RESYNC_REQUEST.containsKey(key) && LAST_RESYNC_REQUEST.size() >= 4_096) {
            MonitorDisplayKey oldest = LAST_RESYNC_REQUEST.entrySet().stream()
                    .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (oldest != null) LAST_RESYNC_REQUEST.remove(oldest);
        }
        LAST_RESYNC_REQUEST.put(key, clientTickTime);
        View view = VIEWS.get(key);
        long sequence = view == null ? 0 : view.snapshot().sequence();
        long generation = view == null ? 0 : view.snapshot().viewGeneration();
        PacketDistributor.sendToServer(new MonitorResyncRequestPayload(key.rootPosition(), key.dimension(),
                key.displayId(), key.loadId(), sequence, generation));
    }

    public static void handle(MonitorOpenPayload payload) {
        ClientLevel level = currentLevel();
        if (!validRoot(level, payload.dimension(), payload.rootPosition(), payload.displayId(), payload.loadId())) return;
        MonitorDisplayKey key = key(payload.dimension(), payload.rootPosition(), payload.displayId(), payload.loadId());
        Minecraft.getInstance().setScreen(new RadarMonitorScreen(key));
    }

    public static View get(MonitorDisplayKey key) { return get(key, 0.0F); }

    public static View get(MonitorDisplayKey key, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = currentLevel();
        if (!validRoot(level, key.dimension(), key.rootPosition(), key.displayId(), key.loadId())) {
            VIEWS.remove(key);
            MonitorSectorAnimationState.remove(key);
            if (minecraft.screen instanceof RadarMonitorScreen screen && screen.isFor(key)) minecraft.setScreen(null);
            return null;
        }
        View stored = VIEWS.get(key);
        if (stored == null) return null;
        return new View(stored.key(), stored.snapshot(), stored.targets(),
                MonitorSectorAnimationState.activeVisuals(key, partialTick));
    }

    public static void clear() {
        VIEWS.clear();
        PENDING_BASELINES.clear();
        PENDING_SEQUENCES.clear();
        PENDING_DELTA_SERIES.clear();
        QUEUED_DELTAS.clear();
        LAST_RESYNC_REQUEST.clear();
        MonitorSectorAnimationState.clear();
        currentLevel = null;
        clientTickTime = 0L;
        lastRenderTime = 0.0D;
    }

    public static void pruneLoadedRoots() {
        ClientLevel level = currentLevel();
        if (level == null) return;
        List<MonitorDisplayKey> invalid = VIEWS.keySet().stream()
                .filter(key -> !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId())).toList();
        for (MonitorDisplayKey key : invalid) {
            VIEWS.remove(key);
            MonitorSectorAnimationState.remove(key);
        }
        Set<MonitorDisplayKey> timedOut = new HashSet<>();
        PENDING_BASELINES.forEach((part, pending) -> {
            if (clientTickTime - pending.createdAt >= PENDING_TIMEOUT_TICKS
                    && matchesLoadedRoot(level, part.display().rootPosition(),
                    part.display().displayId(), part.display().loadId())) timedOut.add(part.display());
        });
        PENDING_DELTA_SERIES.forEach((part, pending) -> {
            if (clientTickTime - pending.createdAt >= PENDING_TIMEOUT_TICKS
                    && matchesLoadedRoot(level, part.display().rootPosition(),
                    part.display().displayId(), part.display().loadId())) timedOut.add(part.display());
        });
        PENDING_BASELINES.entrySet().removeIf(entry -> clientTickTime - entry.getValue().createdAt >= PENDING_TIMEOUT_TICKS
                || !matchesLoadedRoot(level, entry.getKey().display().rootPosition(),
                entry.getKey().display().displayId(), entry.getKey().display().loadId()));
        PENDING_DELTA_SERIES.entrySet().removeIf(entry -> clientTickTime - entry.getValue().createdAt >= PENDING_TIMEOUT_TICKS
                || !matchesLoadedRoot(level, entry.getKey().display().rootPosition(),
                entry.getKey().display().displayId(), entry.getKey().display().loadId()));
        QUEUED_DELTAS.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(delta -> {
                boolean expired = clientTickTime - delta.createdAt() >= PENDING_TIMEOUT_TICKS;
                if (expired && matchesLoadedRoot(level, entry.getKey().rootPosition(),
                        entry.getKey().displayId(), entry.getKey().loadId())) timedOut.add(entry.getKey());
                return expired;
            });
            return entry.getValue().isEmpty() || !matchesLoadedRoot(level, entry.getKey().rootPosition(),
                    entry.getKey().displayId(), entry.getKey().loadId());
        });
        for (MonitorDisplayKey key : timedOut) requestResync(key);
        PENDING_SEQUENCES.keySet().removeIf(key -> !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId())
                || PENDING_BASELINES.keySet().stream().noneMatch(part -> part.display().equals(key)));
        LAST_RESYNC_REQUEST.keySet().removeIf(key -> !matchesLoadedRoot(level, key.rootPosition(),
                key.displayId(), key.loadId()));
    }

    private static void apply(MonitorDisplayKey key, MonitorSnapshotData snapshot) {
        View previous = VIEWS.get(key);
        ArrayList<DisplayTarget> displayTargets = new ArrayList<>(snapshot.targets().size());
        double receivedAt = clientTickTime;
        for (MonitorTargetData target : snapshot.targets()) {
            boolean remembered = target.hasPosition() && !target.observing();
            double fadeStart = remembered
                    ? receivedAt + target.freezeTick() - snapshot.observedServerTick() : 0.0D;
            long fadeDuration = remembered ? target.fadeDurationTicks() : 0L;
            displayTargets.add(new DisplayTarget(target.target(), target.observing(), target.hasPosition(),
                    target.x(), target.y(), target.z(), target.entityTypeId(), remembered,
                    fadeStart, fadeDuration));
        }
        MonitorSectorAnimationState.accept(key, snapshot, receivedAt);
        VIEWS.put(key, new View(key, snapshot, displayTargets, List.of()));
        if (previous == null || previous.snapshot().publishedEpoch() != snapshot.publishedEpoch()) {
            RadarDiagnostics.log("monitor_state_applied", null, null, snapshot.publishedEpoch(), clientTickTime,
                    "displayId=" + snapshot.displayId() + " sequence=" + snapshot.sequence()
                            + " generation=" + snapshot.viewGeneration() + " targets=" + displayTargets.size()
                            + " observing=" + displayTargets.stream().filter(DisplayTarget::observing).count()
                            + " remembered=" + displayTargets.stream().filter(DisplayTarget::rememberedPoint).count());
        }
    }

    static boolean matchesLoadedRoot(ClientLevel level, net.minecraft.core.BlockPos position,
                                     UUID displayId, UUID loadId) {
        if (level == null || !level.hasChunkAt(position)) return false;
        BlockEntity blockEntity = level.getBlockEntity(position);
        return blockEntity instanceof RadarDisplayBlockEntity root && root.isRoot()
                && displayId.equals(root.displayId()) && loadId.equals(root.loadId());
    }

    private static boolean validRoot(ClientLevel level, net.minecraft.resources.ResourceLocation dimension,
                                     net.minecraft.core.BlockPos position, UUID displayId, UUID loadId) {
        return level != null && level.dimension().location().equals(dimension)
                && matchesLoadedRoot(level, position, displayId, loadId);
    }

    private static MonitorDisplayKey key(net.minecraft.resources.ResourceLocation dimension,
                                         net.minecraft.core.BlockPos root, UUID displayId, UUID loadId) {
        return new MonitorDisplayKey(dimension, root, displayId, loadId);
    }

    private static int pendingBaselineCount() {
        return (int) PENDING_BASELINES.keySet().stream().map(BaselinePartKey::display).distinct().count();
    }

    private static boolean sameBaselineHeader(MonitorSnapshotPayload left, MonitorSnapshotPayload right) {
        return left.viewGeneration() == right.viewGeneration() && left.sequence() == right.sequence()
                && left.partCount() == right.partCount() && left.structureVersion() == right.structureVersion()
                && left.displayId().equals(right.displayId())
                && left.rootPosition().equals(right.rootPosition()) && left.dimension().equals(right.dimension())
                && left.loadId().equals(right.loadId()) && left.revision() == right.revision()
                && left.measurementRevision() == right.measurementRevision()
                && left.publishedEpoch() == right.publishedEpoch()
                && left.observedServerTick() == right.observedServerTick()
                && left.linked() == right.linked()
                && left.selectedTargetUnavailable() == right.selectedTargetUnavailable()
                && java.util.Objects.equals(left.selectedTarget(), right.selectedTarget());
    }

    private static boolean sameDeltaHeader(MonitorDeltaPayload left, MonitorDeltaPayload right) {
        return left.viewGeneration() == right.viewGeneration() && left.fromSequence() == right.fromSequence()
                && left.toSequence() == right.toSequence() && left.partCount() == right.partCount()
                && left.structureVersion() == right.structureVersion()
                && left.displayId().equals(right.displayId()) && left.rootPosition().equals(right.rootPosition())
                && left.dimension().equals(right.dimension()) && left.loadId().equals(right.loadId())
                && left.publishedEpoch() == right.publishedEpoch() && left.revision() == right.revision()
                && left.measurementRevision() == right.measurementRevision()
                && left.observedServerTick() == right.observedServerTick()
                && left.linked() == right.linked()
                && left.selectedTargetUnavailable() == right.selectedTargetUnavailable()
                && java.util.Objects.equals(left.selectedTarget(), right.selectedTarget());
    }

    static void advanceClientClock() {
        ClientLevel level = currentLevel();
        if (level != null && !Minecraft.getInstance().isPaused()) clientTickTime++;
    }

    static long clientTickTimeForDiagnostics() { return clientTickTime; }

    public static double renderTime(float partialTick) {
        currentLevel();
        lastRenderTime = Math.max(lastRenderTime, clientTickTime + Math.clamp(partialTick, 0.0F, 1.0F));
        return lastRenderTime;
    }

    private static ClientLevel currentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != currentLevel) {
            VIEWS.clear();
            PENDING_BASELINES.clear();
            PENDING_SEQUENCES.clear();
            PENDING_DELTA_SERIES.clear();
            QUEUED_DELTAS.clear();
            LAST_RESYNC_REQUEST.clear();
            MonitorSectorAnimationState.clear();
            currentLevel = level;
            clientTickTime = 0L;
            lastRenderTime = 0.0D;
        }
        return level;
    }

    private record BaselinePartKey(MonitorDisplayKey display, long sequence,
                                   long generation, int partCount) { }
    private record DeltaPartKey(MonitorDisplayKey display, long generation,
                                long fromSequence, long toSequence, int partCount) { }
    private record CompleteDelta(MonitorDeltaPayload header, List<MonitorTargetData> upserts,
                                 List<TargetRef> removes, List<MonitorVisualPhase> visualPhases,
                                 long createdAt) {
        private CompleteDelta(MonitorDeltaPayload header, List<MonitorTargetData> upserts,
                              List<TargetRef> removes, List<MonitorVisualPhase> visualPhases) {
            this(header, List.copyOf(upserts), List.copyOf(removes), List.copyOf(visualPhases), clientTickTime);
        }
    }

    private static final class PendingBaseline {
        final List<MonitorTargetData>[] targets;
        final List<RadarVisualProfile>[] profiles;
        final List<MonitorVisualPhase>[] phases;
        final long createdAt;
        int received;
        MonitorSnapshotPayload metadata;
        @SuppressWarnings("unchecked")
        PendingBaseline(int count, long createdAt) {
            if (count < 1 || count > MAX_PENDING_PARTS) throw new IllegalArgumentException("Invalid baseline part count");
            targets = (List<MonitorTargetData>[]) new List<?>[count];
            profiles = (List<RadarVisualProfile>[]) new List<?>[count];
            phases = (List<MonitorVisualPhase>[]) new List<?>[count];
            this.createdAt = createdAt;
        }
    }

    private static final class PendingDelta {
        final List<MonitorTargetData>[] upserts;
        final List<TargetRef>[] removes;
        final List<MonitorVisualPhase>[] phases;
        final long createdAt;
        int received;
        boolean hasHeader;
        MonitorDeltaPayload metadata;
        @SuppressWarnings("unchecked")
        PendingDelta(int count, long createdAt) {
            if (count < 1 || count > MAX_PENDING_PARTS) throw new IllegalArgumentException("Invalid delta part count");
            upserts = (List<MonitorTargetData>[]) new List<?>[count];
            removes = (List<TargetRef>[]) new List<?>[count];
            phases = (List<MonitorVisualPhase>[]) new List<?>[count];
            this.createdAt = createdAt;
        }
    }
}
