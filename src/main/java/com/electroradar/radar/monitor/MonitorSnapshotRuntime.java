package com.electroradar.radar.monitor;

import java.util.List;
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
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSnapshot;
import com.electroradar.radar.network.TargetView;

/**
 * Единственный владелец серверного снимка, sequence и расписания его отправки для корня.
 * BlockEntity управляет связью и передаёт сюда только проверенный открытый handle либо null.
 * Вызывается на основном серверном потоке; состояние не сохраняется в NBT.
 */
final class MonitorSnapshotRuntime {
    private final MonitorVisualProfileCollector visualProfiles = new MonitorVisualProfileCollector();
    private long nextSnapshotRefreshGameTime = -1L;
    private MonitorSnapshotData latestSnapshot;
    /** Сбрасывается только одновременно с новым loadId владельца. */
    private long snapshotSequence;
    private long lastSentSequence = -1L;

    void resetForLoad() {
        invalidate();
        clearSchedule();
        snapshotSequence = 0L;
        lastSentSequence = -1L;
        visualProfiles.reset();
    }

    void invalidate() {
        latestSnapshot = null;
    }

    boolean refreshDue(long gameTime) {
        return nextSnapshotRefreshGameTime >= 0L && gameTime >= nextSnapshotRefreshGameTime;
    }

    void refreshAndBroadcast(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                             ConsumerConnection connection, boolean openGui, ServerPlayer recipient) {
        TargetSnapshot<MonitorTargetData> targetSnapshot = connection == null
                ? TargetSnapshot.empty(0, 0)
                : connection.queryTargets(MonitorSnapshotRuntime::projectTarget);
        VisualWindow visualWindow = connection == null ? VisualWindow.EMPTY
                : visualProfiles.collect(serverLevel, owner.networkId(), owner.getBlockPos());
        scheduleSnapshotRefresh(serverLevel, visualWindow);

        updateSnapshot(owner, serverLevel, connection, targetSnapshot, visualWindow);
        send(owner, serverLevel, visualWindow, openGui, recipient);
    }

    private void updateSnapshot(RadarDisplayBlockEntity owner, ServerLevel serverLevel,
                                ConsumerConnection connection, TargetSnapshot<MonitorTargetData> targetSnapshot,
                                VisualWindow visualWindow) {
        boolean linked = owner.networkId() != null;
        boolean sameVersion = latestSnapshot != null
                && latestSnapshot.structureVersion() == owner.structureVersion()
                && latestSnapshot.publishedEpoch() == targetSnapshot.publishedEpoch()
                && latestSnapshot.revision() == targetSnapshot.revision()
                && latestSnapshot.linked() == linked
                && latestSnapshot.visualProfiles().equals(visualWindow.profiles())
                && latestSnapshot.visualWindowPeriodTicks() == visualWindow.periodTicks();
        if (sameVersion) return;
        Selection selection = selection(connection, targetSnapshot.targets());

        if (latestSnapshot == null
                || !latestSnapshot.visualProfiles().equals(visualWindow.profiles())) {
            RadarMonitorDiagnostics.visualGeometryChanged(serverLevel, owner,
                    visualWindow.profiles());
        }
        latestSnapshot = new MonitorSnapshotData(owner.displayId(), owner.getBlockPos(),
                serverLevel.dimension().location(), owner.loadId(), ++snapshotSequence, owner.structureVersion(),
                targetSnapshot.publishedEpoch(), targetSnapshot.revision(), linked,
                selection.unavailable(), selection.target(), targetSnapshot.targets(), visualWindow.profiles(),
                visualWindow.periodTicks(), visualWindow.remainingTicks());
        RadarMonitorDiagnostics.snapshotReceived(serverLevel, owner, latestSnapshot);
    }

    /** Не передаёт клиенту даже идентичность выбранной цели, недоступной этому монитору. */
    private static Selection selection(ConsumerConnection connection, List<MonitorTargetData> targets) {
        TargetRef chosen = null;
        boolean unavailable = false;
        if (connection != null) {
            Optional<TargetRef> networkSelection = connection.selectedTarget();
            if (networkSelection.isPresent()) {
                TargetRef requested = networkSelection.get();
                if (targets.stream().anyMatch(target -> target.target().equals(requested))) {
                    chosen = requested;
                } else {
                    unavailable = true;
                }
            }
        }

        return new Selection(chosen, unavailable);
    }

    private void send(RadarDisplayBlockEntity owner, ServerLevel serverLevel, VisualWindow visualWindow,
                      boolean openGui, ServerPlayer recipient) {
        if (recipient != null) {
            MonitorSnapshotData addressedSnapshot = latestSnapshot.withWindowTiming(
                    visualWindow.periodTicks(), visualWindow.remainingTicks());
            MonitorPayloads.sendToPlayer(recipient, addressedSnapshot, openGui);
            RadarMonitorDiagnostics.snapshotSentAddressed(
                    serverLevel, owner, addressedSnapshot, recipient, openGui);
            // Адресная отправка обслуживает только этого игрока и не подтверждает
            // доставку остальным наблюдателям, подписанным на чанки дисплея.
            return;
        }
        if (openGui || latestSnapshot.sequence() != lastSentSequence) {
            MonitorPayloads.sendToTracking(serverLevel, latestSnapshot);
            RadarMonitorDiagnostics.snapshotBroadcast(serverLevel, owner, latestSnapshot);
            lastSentSequence = latestSnapshot.sequence();
        }
    }

    /**
     * Переводит ближайшую границу часов менеджера в одноразовый дедлайн игровых тиков мира.
     * Часы обоих механизмов продвигаются один раз за серверный тик; разница вычисляется при
     * чтении расписания, поэтому не требуется запрашивать полный снимок на каждом тике.
     * При отсутствии профилей или расписания дедлайн снимается, и остаётся обычный опрос.
     */
    private void scheduleSnapshotRefresh(ServerLevel serverLevel, VisualWindow visualWindow) {
        if (visualWindow.profiles().isEmpty() || visualWindow.periodTicks() <= 0L
                || visualWindow.nextBoundaryTick() < 0L || visualWindow.observedServerTick() < 0L) {
            clearSchedule();
            return;
        }
        long ticksUntilBoundary = Math.max(
                0L, visualWindow.nextBoundaryTick() - visualWindow.observedServerTick());
        nextSnapshotRefreshGameTime = serverLevel.getGameTime() + ticksUntilBoundary;
    }

    /** Сбрасывает runtime-дедлайн при потере связи, корня или расписания. */
    void clearSchedule() {
        nextSnapshotRefreshGameTime = -1L;
    }

    private static MonitorTargetData projectTarget(TargetView view) {
        TargetRef target = view.target();
        LiveResult live = view.liveResult();
        if (!view.detectedThisWindow() || live.position().isEmpty() || live.entityType().isEmpty()) {
            return new MonitorTargetData(target, view.detectedThisWindow(), false, 0, 0, 0, "");
        }
        Vec3 position = live.position().orElseThrow();
        EntityType<?> type = live.entityType().orElseThrow();
        return new MonitorTargetData(target, true, true, position.x, position.y, position.z,
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    private record Selection(TargetRef target, boolean unavailable) {
    }
}
