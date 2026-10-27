package com.electroradar.radar.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.monitor.MonitorVisualProfileCollector.VisualWindow;
import com.electroradar.radar.monitor.network.MonitorPayloads;
import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSnapshot;
import com.electroradar.radar.network.TargetView;
import com.electroradar.radar.network.RadarNetworkManager;

/** Вычисляет версии корневого экрана и маршрутизирует baseline/delta без истории по потребителям. */
final class MonitorSnapshotRuntime {
    private final MonitorVisualProfileCollector visualProfiles = new MonitorVisualProfileCollector();
    private MonitorSnapshotData latestSnapshot;
    private long snapshotSequence;
    private long lastBroadcastSequence = -1L;
    private long viewGeneration;
    private NetworkId lastNetworkId;
    private boolean networkContextInitialized;
    private boolean baselineRequired = true;
    private final Set<TargetRef> changedTargetsSinceBroadcast = new HashSet<>();

    void resetForLoad() {
        latestSnapshot = null;
        snapshotSequence = 0L;
        lastBroadcastSequence = -1L;
        viewGeneration = 0L;
        lastNetworkId = null;
        networkContextInitialized = false;
        baselineRequired = true;
        changedTargetsSinceBroadcast.clear();
        visualProfiles.reset();
    }

    void invalidate() { baselineRequired = true; }

    void refreshAndBroadcast(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                             ConsumerConnection connection, boolean openGui, ServerPlayer recipient) {
        TargetSnapshot<TargetRef> identities = connection == null
                ? TargetSnapshot.empty(0, 0) : connection.queryTargetRefs();
        TargetSnapshot<MonitorTargetData> targetSnapshot = connection == null
                ? TargetSnapshot.empty(0, 0)
                : connection.queryTargetDetails(identities.targets(), MonitorSnapshotRuntime::projectTarget);
        VisualWindow visualWindow = connection == null ? VisualWindow.EMPTY
                : visualProfiles.collect(serverLevel, owner.networkId(), owner.getBlockPos());
        updateSnapshot(owner, serverLevel, connection, targetSnapshot, visualWindow);
        send(owner, serverLevel, openGui, recipient);
    }

    private void updateSnapshot(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                                ConsumerConnection connection, TargetSnapshot<MonitorTargetData> targets,
                                VisualWindow visualWindow) {
        boolean linked = owner.networkId() != null;
        NetworkId networkId = owner.networkId();
        boolean networkChanged = networkContextInitialized && !Objects.equals(lastNetworkId, networkId);
        boolean geometryChanged = latestSnapshot != null
                && (!latestSnapshot.visualProfiles().equals(visualWindow.profiles())
                || !sameGeometryGeneration(latestSnapshot.visualPhases(), visualWindow.phases()));
        boolean structureChanged = latestSnapshot != null
                && latestSnapshot.structureVersion() != owner.structureVersion();
        if (latestSnapshot != null && (networkChanged || geometryChanged || structureChanged)) {
            viewGeneration = Math.incrementExact(viewGeneration);
            baselineRequired = true;
        } else if (latestSnapshot == null) {
            viewGeneration = 0L;
            baselineRequired = true;
        }
        lastNetworkId = networkId;
        networkContextInitialized = true;

        Selection selection = selection(connection, targets.targets());
        long observedTick = visualWindow.observedServerTick() < 0
                ? serverLevel.getGameTime() : visualWindow.observedServerTick();
        // Сравниваем только то, что видит клиент. Номера epoch/revision/measurementRevision
        // растут каждый тик, пока хоть одна цель наблюдается, даже если отметки не сдвинулись;
        // если считать их изменением, каждый тик уходит новая sequence и delta всем наблюдателям.
        boolean unchanged = latestSnapshot != null
                && latestSnapshot.structureVersion() == owner.structureVersion()
                && latestSnapshot.viewGeneration() == viewGeneration
                && latestSnapshot.linked() == linked
                && latestSnapshot.selectedTargetUnavailable() == selection.unavailable()
                && Objects.equals(latestSnapshot.selectedTarget(), selection.target())
                && latestSnapshot.targets().equals(targets.targets())
                && latestSnapshot.visualProfiles().equals(visualWindow.profiles())
                && latestSnapshot.visualPhases().equals(visualWindow.phases());
        if (unchanged) return;

        if (latestSnapshot == null || geometryChanged) {
            RadarMonitorDiagnostics.visualGeometryChanged(serverLevel, owner, visualWindow.profiles());
        }
        MonitorSnapshotData next = new MonitorSnapshotData(owner.displayId(), owner.getBlockPos(),
                serverLevel.dimension().location(), owner.loadId(), ++snapshotSequence, owner.structureVersion(),
                targets.publishedEpoch(), targets.revision(), linked, selection.unavailable(), selection.target(),
                targets.targets(), visualWindow.profiles(),
                targets.measurementRevision(), viewGeneration, observedTick, visualWindow.phases());
        collectChangedTargets(latestSnapshot, next);
        latestSnapshot = next;
        RadarMonitorDiagnostics.snapshotReceived(serverLevel, owner, latestSnapshot);
    }

    private void collectChangedTargets(MonitorSnapshotData previous, MonitorSnapshotData next) {
        Map<TargetRef, MonitorTargetData> oldTargets = new HashMap<>();
        if (previous != null) for (MonitorTargetData target : previous.targets()) oldTargets.put(target.target(), target);
        Map<TargetRef, MonitorTargetData> newTargets = new HashMap<>();
        for (MonitorTargetData target : next.targets()) newTargets.put(target.target(), target);
        Set<TargetRef> all = new HashSet<>(oldTargets.keySet());
        all.addAll(newTargets.keySet());
        for (TargetRef target : all) {
            if (!Objects.equals(oldTargets.get(target), newTargets.get(target))) {
                changedTargetsSinceBroadcast.add(target);
            }
        }
    }

    private static boolean sameGeometryGeneration(List<MonitorVisualPhase> left, List<MonitorVisualPhase> right) {
        if (left.size() != right.size()) return false;
        Map<net.minecraft.core.BlockPos, Long> revisions = new HashMap<>();
        for (MonitorVisualPhase phase : left) revisions.put(phase.radarPosition(), phase.geometryRevision());
        for (MonitorVisualPhase phase : right) {
            if (!Objects.equals(revisions.get(phase.radarPosition()), phase.geometryRevision())) return false;
        }
        return true;
    }

    /** Не раскрывает выбранную цель, если текущая регистрация уже не может прочитать её. */
    private static Selection selection(ConsumerConnection connection, List<MonitorTargetData> targets) {
        if (connection == null) return new Selection(null, false);
        Optional<TargetRef> requested = connection.selectedTarget();
        if (requested.isEmpty()) return new Selection(null, false);
        TargetRef target = requested.orElseThrow();
        return targets.stream().anyMatch(value -> value.target().equals(target))
                ? new Selection(target, false) : new Selection(null, true);
    }

    private void send(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                      boolean openGui, ServerPlayer recipient) {
        if (latestSnapshot == null) return;
        boolean needsBaseline = baselineRequired || lastBroadcastSequence < 0L;
        // Тихий тик не требует пересоздания и полной валидации исходящего снимка.
        // Адресный baseline и обязательная рассылка нужны даже без новой sequence.
        if (recipient == null && !needsBaseline
                && latestSnapshot.sequence() == lastBroadcastSequence) return;
        // Снимок мог быть собран тиком раньше (адресная отправка при передаче чанка, или
        // содержимое не менялось), а клиент пересчитывает freeze и фазу от observedServerTick.
        // Поэтому любая отправка, не только адресная, несёт тик самой отправки.
        long serverTick = RadarNetworkManager.forServer(serverLevel.getServer()).currentServerTick();
        MonitorSnapshotData outgoing = latestSnapshot.withServerTiming(serverTick);
        if (recipient != null) {
            MonitorPayloads.sendToPlayer(recipient, outgoing, openGui);
            RadarMonitorDiagnostics.snapshotSentAddressed(serverLevel, owner, outgoing, recipient, openGui);
            return;
        }
        if (needsBaseline) {
            MonitorPayloads.sendToTracking(serverLevel, outgoing);
            RadarMonitorDiagnostics.snapshotBroadcast(serverLevel, owner, latestSnapshot);
            lastBroadcastSequence = latestSnapshot.sequence();
            changedTargetsSinceBroadcast.clear();
            baselineRequired = false;
            return;
        }
        Map<TargetRef, MonitorTargetData> current = new HashMap<>();
        for (MonitorTargetData target : latestSnapshot.targets()) current.put(target.target(), target);
        List<MonitorTargetData> upserts = new ArrayList<>();
        List<TargetRef> removes = new ArrayList<>();
        for (TargetRef target : changedTargetsSinceBroadcast.stream().sorted(TargetRef.ORDER).toList()) {
            MonitorTargetData value = current.get(target);
            if (value == null) removes.add(target); else upserts.add(value);
        }
        MonitorPayloads.sendDeltaToTracking(serverLevel, outgoing, lastBroadcastSequence, upserts, removes);
        RadarMonitorDiagnostics.snapshotBroadcast(serverLevel, owner, latestSnapshot);
        lastBroadcastSequence = latestSnapshot.sequence();
        changedTargetsSinceBroadcast.clear();
    }

    private static MonitorTargetData projectTarget(TargetView view) {
        TargetRef target = view.target();
        LiveResult live = view.liveResult();
        if (live.position().isEmpty() || live.entityType().isEmpty()) {
            return new MonitorTargetData(target, false, false, 0, 0, 0, "",
                    live.sampledTick(), live.freezeTick(), live.fadeDurationTicks());
        }
        Vec3 position = live.position().orElseThrow();
        EntityType<?> type = live.entityType().orElseThrow();
        // У наблюдаемой цели sample всегда равен текущему тику, клиенту он не нужен. Если его
        // передавать, неподвижная цель (стойка, вагонетка, собственная апертура радара) меняет
        // запись каждый тик и порождает upsert. Настоящий sampledTick уходит вместе с freeze.
        long sampledTick = live.observing() ? 0L : live.sampledTick();
        return new MonitorTargetData(target, live.observing(), true, position.x, position.y, position.z,
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), sampledTick,
                live.freezeTick(), live.fadeDurationTicks());
    }

    private record Selection(TargetRef target, boolean unavailable) { }
}
