package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.network.MonitorGeometryRequestPayload;
import com.electroradar.radar.monitor.network.MonitorOpenPayload;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Клиентский кэш мониторов: последний полный снимок, геометрия радаров и сборка частей.
 *
 * <p>Каждый снимок полный, поэтому новый просто заменяет старый. Старые и повторные серии
 * отбрасываются по {@code sequence}. Геометрия хранится отдельно с её {@code geometryVersion}:
 * снимок без профилей использует сохранённые профили той же версии. Если их нет (снимок с
 * геометрией пришёл раньше, чем загрузился корень), сектора не рисуются до ближайшего
 * снимка с геометрией — его сервер шлёт при передаче чанка и при каждой смене профилей.</p>
 */
public final class MonitorClientState {
    private static final int MAX_PENDING_SERIES = 16;
    private static final long PENDING_TIMEOUT_TICKS = 200L;
    private static final Map<MonitorDisplayKey, View> VIEWS = new HashMap<>();
    private static final Map<MonitorDisplayKey, Geometry> GEOMETRY = new HashMap<>();
    private static final Map<MonitorDisplayKey, PendingSeries> PENDING = new HashMap<>();
    private static final long GEOMETRY_REQUEST_COOLDOWN_TICKS = 20L;
    private static final Map<MonitorDisplayKey, Long> GEOMETRY_REQUESTS = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long clientTickTime;
    private static double lastRenderTime;

    private MonitorClientState() { }

    /** Клиентская отметка: последнее разрешённое сервером положение и параметры затухания. */
    public record DisplayTarget(TargetRef target, boolean observing, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                boolean rememberedPoint, double rememberedFadeStartClientTick,
                                long rememberedFadeDurationTicks) {
        public boolean detectedThisWindow() { return observing; }

        public float rememberedOpacity(double clientRenderTime) {
            if (!rememberedPoint) return 1.0F;
            if (rememberedFadeDurationTicks <= 0L) return 0.0F;
            double age = Math.max(0.0D, clientRenderTime - rememberedFadeStartClientTick);
            return (float) Math.clamp(1.0D - age / rememberedFadeDurationTicks, 0.0D, 1.0D);
        }
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

    /** Принимает одну часть; снимок применяется, когда собрана вся серия. */
    public static void accept(MonitorSnapshotPayload part) {
        ClientLevel level = currentLevel();
        if (!validRoot(level, part.dimension(), part.rootPosition(), part.displayId(), part.loadId())) return;
        MonitorDisplayKey key = new MonitorDisplayKey(part.dimension(), part.rootPosition(),
                part.displayId(), part.loadId());
        if (!newerThanView(key, part)) return;

        PendingSeries pending = PENDING.get(key);
        if (pending != null && !pending.header.sameSeries(part)) {
            if (part.sequence() < pending.header.sequence()) return;
            pending = null;
        }
        if (pending == null) {
            if (!PENDING.containsKey(key) && PENDING.size() >= MAX_PENDING_SERIES) return;
            pending = new PendingSeries(part, clientTickTime);
            PENDING.put(key, pending);
        }
        if (!pending.add(part) || !pending.complete()) return;
        PENDING.remove(key);

        MonitorSnapshotPayload header = pending.header;
        List<RadarVisualProfile> profiles;
        List<MonitorVisualPhase> phases = pending.phases();
        if (header.includesGeometry()) {
            profiles = pending.profiles();
            GEOMETRY.put(key, new Geometry(header.geometryVersion(), profiles));
        } else {
            Geometry stored = GEOMETRY.get(key);
            profiles = stored != null && stored.version() == header.geometryVersion() ? stored.profiles() : null;
        }
        if (profiles == null) {
            // Геометрии этой версии нет: показываем цели без секторов и просим её у сервера.
            profiles = List.of();
            phases = List.of();
            requestGeometry(key, header.geometryVersion());
        }
        MonitorSnapshotData snapshot;
        try {
            snapshot = new MonitorSnapshotData(header.displayId(), header.rootPosition(), header.dimension(),
                    header.loadId(), header.sequence(), header.geometryVersion(), header.observedServerTick(),
                    header.linked(), header.selectedTargetUnavailable(), header.selectedTarget(),
                    pending.targets(), profiles, phases);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        apply(key, snapshot);
    }

    /**
     * Снимок нужен, если его номер больше показанного. Равный номер принимается только тогда,
     * когда серия несёт геометрию, которой у клиента нет: так адресная отправка чинит снимок,
     * принятый без профилей.
     */
    private static boolean newerThanView(MonitorDisplayKey key, MonitorSnapshotPayload part) {
        View current = VIEWS.get(key);
        if (current == null || part.sequence() > current.snapshot().sequence()) return true;
        if (part.sequence() < current.snapshot().sequence() || !part.includesGeometry()) return false;
        Geometry stored = GEOMETRY.get(key);
        return stored == null || stored.version() != part.geometryVersion();
    }

    /**
     * Просит недостающую геометрию не чаще раза в {@link #GEOMETRY_REQUEST_COOLDOWN_TICKS} на экран.
     * Снимки без геометрии приходят минимум каждое окно сети, поэтому повтор после отказа
     * или потери ответа происходит сам; отдельная очередь не нужна.
     */
    private static void requestGeometry(MonitorDisplayKey key, long geometryVersion) {
        Long last = GEOMETRY_REQUESTS.get(key);
        if (last != null && clientTickTime - last < GEOMETRY_REQUEST_COOLDOWN_TICKS) return;
        GEOMETRY_REQUESTS.put(key, clientTickTime);
        PacketDistributor.sendToServer(new MonitorGeometryRequestPayload(key.rootPosition(), key.dimension(),
                key.displayId(), key.loadId(), geometryVersion));
    }

    public static void handle(MonitorOpenPayload payload) {
        ClientLevel level = currentLevel();
        if (!validRoot(level, payload.dimension(), payload.rootPosition(), payload.displayId(), payload.loadId())) return;
        MonitorDisplayKey key = new MonitorDisplayKey(payload.dimension(), payload.rootPosition(),
                payload.displayId(), payload.loadId());
        Minecraft.getInstance().setScreen(new RadarMonitorScreen(key));
    }

    public static View get(MonitorDisplayKey key) { return get(key, 0.0F); }

    public static View get(MonitorDisplayKey key, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = currentLevel();
        if (!validRoot(level, key.dimension(), key.rootPosition(), key.displayId(), key.loadId())) {
            forget(key);
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
        GEOMETRY.clear();
        PENDING.clear();
        GEOMETRY_REQUESTS.clear();
        MonitorSectorAnimationState.clear();
        currentLevel = null;
        clientTickTime = 0L;
        lastRenderTime = 0.0D;
    }

    /** Убирает состояние экранов, чей корень выгружен или заменён, и брошенные серии частей. */
    public static void pruneLoadedRoots() {
        ClientLevel level = currentLevel();
        if (level == null) return;
        List<MonitorDisplayKey> invalid = new ArrayList<>();
        for (MonitorDisplayKey key : VIEWS.keySet()) if (!matchesRoot(level, key)) invalid.add(key);
        for (MonitorDisplayKey key : GEOMETRY.keySet()) if (!matchesRoot(level, key)) invalid.add(key);
        for (MonitorDisplayKey key : invalid) forget(key);
        GEOMETRY_REQUESTS.keySet().removeIf(key -> !matchesRoot(level, key));
        PENDING.entrySet().removeIf(entry -> clientTickTime - entry.getValue().createdAt >= PENDING_TIMEOUT_TICKS
                || !matchesRoot(level, entry.getKey()));
    }

    private static void forget(MonitorDisplayKey key) {
        VIEWS.remove(key);
        GEOMETRY.remove(key);
        PENDING.remove(key);
        GEOMETRY_REQUESTS.remove(key);
        MonitorSectorAnimationState.remove(key);
    }

    private static void apply(MonitorDisplayKey key, MonitorSnapshotData snapshot) {
        ArrayList<DisplayTarget> displayTargets = new ArrayList<>(snapshot.targets().size());
        double receivedAt = clientTickTime;
        for (MonitorTargetData target : snapshot.targets()) {
            boolean remembered = target.hasPosition() && !target.observing();
            // freezeTick переводится в клиентское время по тику отправки снимка.
            double fadeStart = remembered ? receivedAt + target.freezeTick() - snapshot.observedServerTick() : 0.0D;
            displayTargets.add(new DisplayTarget(target.target(), target.observing(), target.hasPosition(),
                    target.x(), target.y(), target.z(), target.entityTypeId(), remembered, fadeStart,
                    remembered ? target.fadeDurationTicks() : 0L));
        }
        MonitorSectorAnimationState.accept(key, snapshot, receivedAt);
        VIEWS.put(key, new View(key, snapshot, displayTargets, List.of()));
        if (RadarDiagnostics.visualDiagnosticsEnabled()) {
            RadarDiagnostics.log("monitor_state_applied", null, null, null, clientTickTime,
                    () -> "displayId=" + snapshot.displayId() + " sequence=" + snapshot.sequence()
                            + " geometryVersion=" + snapshot.geometryVersion()
                            + " targets=" + displayTargets.size()
                            + " profiles=" + snapshot.visualProfiles().size());
        }
    }

    static boolean matchesLoadedRoot(ClientLevel level, BlockPos position, UUID displayId, UUID loadId) {
        if (level == null || !level.hasChunkAt(position)) return false;
        BlockEntity blockEntity = level.getBlockEntity(position);
        return blockEntity instanceof RadarDisplayBlockEntity root && root.isRoot()
                && displayId.equals(root.displayId()) && loadId.equals(root.loadId());
    }

    private static boolean matchesRoot(ClientLevel level, MonitorDisplayKey key) {
        return matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId());
    }

    private static boolean validRoot(ClientLevel level, ResourceLocation dimension,
                                     BlockPos position, UUID displayId, UUID loadId) {
        return level != null && level.dimension().location().equals(dimension)
                && matchesLoadedRoot(level, position, displayId, loadId);
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
            GEOMETRY.clear();
            PENDING.clear();
            GEOMETRY_REQUESTS.clear();
            MonitorSectorAnimationState.clear();
            currentLevel = level;
            clientTickTime = 0L;
            lastRenderTime = 0.0D;
        }
        return level;
    }

    private record Geometry(long version, List<RadarVisualProfile> profiles) {
        private Geometry {
            profiles = List.copyOf(profiles);
        }
    }

    /** Части одной серии; заголовок первой полученной части задаёт серию. */
    private static final class PendingSeries {
        final MonitorSnapshotPayload header;
        final MonitorSnapshotPayload[] parts;
        final long createdAt;
        int received;

        PendingSeries(MonitorSnapshotPayload header, long createdAt) {
            this.header = header;
            this.parts = new MonitorSnapshotPayload[header.partCount()];
            this.createdAt = createdAt;
        }

        /** @return false для повтора уже полученной части */
        boolean add(MonitorSnapshotPayload part) {
            if (parts[part.partIndex()] != null) return false;
            parts[part.partIndex()] = part;
            received++;
            return true;
        }

        boolean complete() { return received == parts.length; }

        List<MonitorTargetData> targets() {
            ArrayList<MonitorTargetData> all = new ArrayList<>();
            for (MonitorSnapshotPayload part : parts) all.addAll(part.targets());
            return all;
        }

        List<RadarVisualProfile> profiles() {
            ArrayList<RadarVisualProfile> all = new ArrayList<>();
            for (MonitorSnapshotPayload part : parts) all.addAll(part.visualProfiles());
            return all;
        }

        List<MonitorVisualPhase> phases() {
            ArrayList<MonitorVisualPhase> all = new ArrayList<>();
            for (MonitorSnapshotPayload part : parts) all.addAll(part.visualPhases());
            return all;
        }
    }
}
