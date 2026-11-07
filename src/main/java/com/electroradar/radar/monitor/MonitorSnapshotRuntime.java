package com.electroradar.radar.monitor;

import com.electroradar.radar.spatial.RadarSpatial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSnapshot;
import com.electroradar.radar.network.TargetView;

/**
 * Единственный владелец снимка корневого экрана, его номеров и истории рассылки.
 *
 * <p><b>Когда пересобирать.</b> Корень тикает каждый тик, но сеть перечитывает только если
 * изменилась версия сети ({@link ConsumerConnection#contentVersion()}), сама связь, NetworkId
 * или сборка экрана, либо снимок явно сброшен. Версия сети растёт при любом видимом
 * изменении, в том числе при каждом шаге движущейся цели, поэтому перечитывание из-за неё
 * идёт не чаще {@link MonitorRefreshGate}; смена связи, сети, сборки, выбора цели и открытие
 * GUI применяются сразу.</p>
 *
 * <p><b>Что отправлять.</b> Всегда полный снимок целей и фаз. Профили радаров тяжёлые, поэтому
 * рассылка включает их только при смене {@code geometryVersion} или после сброса. Адресная
 * отправка (передача чанка, открытие GUI) всегда несёт геометрию и не подтверждает рассылку
 * остальным наблюдателям. Пакеты Minecraft надёжны и упорядочены, поэтому восстановления по
 * запросу и очередей частей нет: следующий снимок полностью заменяет предыдущий.</p>
 */
final class MonitorSnapshotRuntime {
    private final MonitorVisualProfileCollector visualProfiles = new MonitorVisualProfileCollector();
    private MonitorSnapshotData latestSnapshot;
    /** Только растут в пределах одной загрузки; сбрасываются лишь вместе с новым loadId. */
    private long snapshotSequence;
    private long geometryVersion;
    private long lastBroadcastSequence = -1L;
    private long lastBroadcastGeometryVersion = -1L;
    /** Следующая рассылка обязательна и несёт геометрию, даже если sequence не изменилась. */
    private boolean broadcastRequired = true;

    private final java.util.Set<java.util.UUID> knownWatchers = new java.util.HashSet<>();
    private final MonitorRefreshGate refreshGate = new MonitorRefreshGate();
    /** Запрошенный выбор сети при последней сборке: его смена видна сразу, без ожидания шага. */
    private TargetRef lastRequestedSelection;
    private boolean rebuildRequired = true;
    private ConsumerConnection lastConnection;
    private long lastContentVersion = Long.MIN_VALUE;
    private long lastStructureVersion = Long.MIN_VALUE;
    private NetworkId lastNetworkId;

    void resetForLoad() {
        knownWatchers.clear();
        refreshGate.reset();
        latestSnapshot = null;
        snapshotSequence = 0L;
        geometryVersion = 0L;
        lastBroadcastSequence = -1L;
        lastBroadcastGeometryVersion = -1L;
        broadcastRequired = true;
        forgetSources();
        visualProfiles.reset();
    }

    /** Сбрасывает снимок после смены сборки, сети или корня: перечитать и разослать целиком. */
    void invalidate() {
        rebuildRequired = true;
        broadcastRequired = true;
    }

    void refreshAndBroadcast(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                             ConsumerConnection connection, boolean openGui, ServerPlayer recipient) {
        boolean urgent = rebuildRequired || latestSnapshot == null || recipient != null
                || connection != lastConnection
                || owner.structureVersion() != lastStructureVersion
                || !Objects.equals(owner.networkId(), lastNetworkId)
                || !Objects.equals(requestedSelection(connection), lastRequestedSelection);
        long now = serverLevel.getGameTime();
        if (!refreshGate.due(now, urgent)) return;
        refreshGate.refreshed(now);
        long contentVersion = connection == null ? -1L : connection.contentVersion();
        boolean dirty = urgent || contentVersion != lastContentVersion;
        if (dirty) {
            rebuild(owner, serverLevel, connection);
            rebuildRequired = false;
            lastConnection = connection;
            lastContentVersion = contentVersion;
            lastStructureVersion = owner.structureVersion();
            lastNetworkId = owner.networkId();
        }
        if (latestSnapshot != null) {
            var watchers = RadarSpatial.backend().watchers(serverLevel, owner.getBlockPos());
            java.util.Set<java.util.UUID> current = new java.util.HashSet<>();
            for (ServerPlayer watcher : watchers) {
                current.add(watcher.getUUID());
                if (!knownWatchers.contains(watcher.getUUID())) {
                    MonitorPayloads.sendToPlayer(watcher, latestSnapshot.withServerTiming(currentTick(serverLevel)), false);
                }
            }
            knownWatchers.retainAll(current);
            knownWatchers.addAll(current);
        }
        send(owner, serverLevel, openGui, recipient);
    }

    private void rebuild(RadarDisplayBlockEntity owner, ServerLevel serverLevel, ConsumerConnection connection) {
        // Без размещения (структура удаляется в этом тике) снимок не строится, прежний остаётся.
        var placement = RadarSpatial.placement(serverLevel, owner.getBlockPos());
        if (placement.isEmpty()) return;
        TargetSnapshot<MonitorTargetData> targets = connection == null
                ? TargetSnapshot.empty() : connection.queryTargets(MonitorSnapshotRuntime::projectTarget);
        VisualWindow visual = connection == null ? VisualWindow.EMPTY
                : visualProfiles.collect(serverLevel, owner.networkId(), owner.getBlockPos());
        Selection selection = selection(connection, targets.targets());
        lastRequestedSelection = requestedSelection(connection);
        boolean geometryChanged = latestSnapshot == null
                || !latestSnapshot.visualProfiles().equals(visual.profiles());
        long candidateGeometry = geometryChanged ? geometryVersion + 1 : geometryVersion;
        MonitorSnapshotData candidate = new MonitorSnapshotData(owner.displayId(), owner.getBlockPos(),
                serverLevel.dimension().location(), owner.loadId(), snapshotSequence + 1, candidateGeometry,
                currentTick(serverLevel), owner.networkId() != null, selection.unavailable(), selection.target(),
                targets.targets(), visual.profiles(), visual.phases(),
                placement.get().endpoint());
        if (candidate.sameContent(latestSnapshot)) return;
        if (geometryChanged) {
            geometryVersion = candidateGeometry;
            RadarMonitorDiagnostics.visualGeometryChanged(serverLevel, owner, visual.profiles());
        }
        snapshotSequence = candidate.sequence();
        latestSnapshot = candidate;
        RadarMonitorDiagnostics.snapshotReceived(serverLevel, owner, latestSnapshot);
    }

    private void send(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                      boolean openGui, ServerPlayer recipient) {
        if (latestSnapshot == null) return;
        if (recipient == null && !broadcastRequired && latestSnapshot.sequence() == lastBroadcastSequence) return;
        // Снимок мог быть собран раньше, а клиент пересчитывает freeze и фазу от observedServerTick.
        MonitorSnapshotData outgoing = latestSnapshot.withServerTiming(currentTick(serverLevel));
        if (recipient != null) {
            MonitorPayloads.sendToPlayer(recipient, outgoing, openGui);
            RadarMonitorDiagnostics.snapshotSentAddressed(serverLevel, owner, outgoing, recipient, openGui);
            return;
        }
        boolean includeGeometry = broadcastRequired || outgoing.geometryVersion() != lastBroadcastGeometryVersion;
        MonitorPayloads.sendToTracking(serverLevel, outgoing, includeGeometry);
        RadarMonitorDiagnostics.snapshotBroadcast(serverLevel, owner, outgoing, includeGeometry);
        lastBroadcastSequence = outgoing.sequence();
        lastBroadcastGeometryVersion = outgoing.geometryVersion();
        broadcastRequired = false;
    }

    private void forgetSources() {
        rebuildRequired = true;
        lastRequestedSelection = null;
        lastConnection = null;
        lastContentVersion = Long.MIN_VALUE;
        lastStructureVersion = Long.MIN_VALUE;
        lastNetworkId = null;
    }

    private static long currentTick(ServerLevel serverLevel) {
        return RadarNetworkManager.forServer(serverLevel.getServer()).currentServerTick();
    }

    private static TargetRef requestedSelection(ConsumerConnection connection) {
        return connection == null || !connection.isOpen() ? null : connection.selectedTarget().orElse(null);
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

    private static MonitorTargetData projectTarget(TargetView view) {
        TargetRef target = view.target();
        LiveResult live = view.liveResult();
        if (live.structure().isPresent() && live.position().isPresent()) {
            Vec3 point = live.position().get();
            return new MonitorTargetData(target, live.observing(), true, point.x, point.y, point.z, "",
                    live.freezeTick(), live.fadeDurationTicks(), live.structure().get().silhouette(),
                    view.registeredStructure(), live.structure().get().scanAnchor());
        }
        if (live.position().isEmpty() || live.entityType().isEmpty()) {
            return new MonitorTargetData(target, false, false, 0, 0, 0, "",
                    live.freezeTick(), live.fadeDurationTicks());
        }
        Vec3 position = live.position().orElseThrow();
        EntityType<?> type = live.entityType().orElseThrow();
        return new MonitorTargetData(target, live.observing(), true, position.x, position.y, position.z,
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), live.freezeTick(), live.fadeDurationTicks());
    }

    private record Selection(TargetRef target, boolean unavailable) { }
}
