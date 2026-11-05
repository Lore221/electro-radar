package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.electroradar.config.MonitorSweepConfig;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualGeometry;

/** Последние показанные координаты; исходный серверный снимок всегда остаётся неизменным. */
final class MonitorTargetSweepState {
    private final Map<TargetRef, Marker> markers = new HashMap<>();
    private MonitorSnapshotData snapshot;
    private double receivedAt;
    private MonitorSnapshotData evaluatedSnapshot;
    private List<MonitorSectorAnimationState.ProfileFrame> evaluatedFrames = List.of();
    private List<MonitorClientState.DisplayTarget> cachedTargets = List.of();
    private double evaluatedAt;

    void accept(MonitorSnapshotData next, double now) {
        if (snapshot == null || !next.linked()) markers.clear();
        snapshot = next;
        receivedAt = now;
        evaluatedSnapshot = null;
        cachedTargets = List.of();
        var permitted = new HashSet<TargetRef>();
        for (MonitorTargetData raw : next.targets()) {
            if (raw.hasPosition()) permitted.add(raw.target());
            Marker marker = markers.get(raw.target());
            if (marker != null) marker.serverMemory(raw, next, now);
        }
        // Отзыв доступа немедленно удаляет и местный след. Кеш картинки не даёт прав.
        markers.keySet().retainAll(permitted);
    }

    List<MonitorClientState.DisplayTarget> targets(MonitorDisplayKey key,
                                                  List<MonitorSectorAnimationState.ProfileFrame> frames, double now) {
        // Посещения короче тика тоже обрабатываются, даже если между ними не было кадра.
        if (evaluatedSnapshot == snapshot) {
            double boundary = nextBoundary();
            while (boundary < now) {
                double afterBoundary = Math.nextUp(boundary);
                evaluate(MonitorSectorAnimationState.visualsAt(key, afterBoundary), afterBoundary);
                boundary = nextBoundary();
            }
        }
        return evaluate(frames, now);
    }

    private double nextBoundary() {
        return evaluatedFrames.stream().mapToDouble(MonitorSectorAnimationState.ProfileFrame::windowEndClientTick)
                .filter(end -> end > evaluatedAt).min().orElse(Double.POSITIVE_INFINITY);
    }

    private List<MonitorClientState.DisplayTarget> evaluate(List<MonitorSectorAnimationState.ProfileFrame> frames, double now) {
        if (snapshot == null || !snapshot.linked()) return List.of();
        // Между пакетами и сменами лучей координаты неизменны; fade вычисляет
        // рендерер по общим часам. Не создаём N записей на каждый кадр GUI/BER.
        if (evaluatedSnapshot == snapshot && sameWindows(frames)) {
            evaluatedAt = now;
            return cachedTargets;
        }
        List<MonitorClientState.DisplayTarget> result = new ArrayList<>(markers.size());
        for (MonitorTargetData raw : snapshot.targets()) {
            if (!raw.hasPosition()) continue;
            double activeUntil = -1, fadeTicks = 0;
            for (var frame : frames) {
                int index = RadarVisualGeometry.sectorIndex(frame.profile(), raw.x(), raw.z());
                boolean active = index >= 0 && (frame.activeSectorIndices().contains(index)
                        || frame.profile().animation() == RadarVisualAnimation.FULL_FLASH && frame.flashStrength() > 0);
                if (active && frame.windowEndClientTick() > activeUntil) {
                    activeUntil = frame.windowEndClientTick();
                    fadeTicks = MonitorSweepConfig.sweep(frame.profile().inactiveSectorFadeStyle()).targetFadeTicks();
                }
            }
            Marker marker = markers.get(raw.target());
            if (activeUntil >= 0) {
                if (marker == null) {
                    marker = new Marker();
                    markers.put(raw.target(), marker);
                }
                marker.shown = raw;
                marker.fadeTicks = fadeTicks;
                marker.activeUntil = activeUntil;
                // Серверный fade независимо ограничивает срок жизни картинки.
                marker.frozenAt = -1;
                marker.serverMemory(raw, snapshot, receivedAt);
            } else if (marker != null && marker.frozenAt < 0) {
                // При смене сектора фиксируем общий момент его выключения, даже если
                // этот монитор несколько кадров не рисовали. Новое положение снаружи
                // активного подсектора в shown не копируется.
                marker.frozenAt = Math.min(now, marker.activeUntil);
                if (!raw.equals(marker.shown)) marker.frozenAt = Math.min(marker.frozenAt, receivedAt);
            }
            if (marker == null) continue;
            MonitorTargetData shown = marker.shown;
            result.add(new MonitorClientState.DisplayTarget(raw.target(), raw.observing(), true,
                    shown.x(), shown.y(), shown.z(), shown.entityTypeId(), marker.frozenAt >= 0,
                    marker.frozenAt, marker.fadeTicks, marker.serverFrozenAt, marker.serverFadeTicks));
        }
        evaluatedSnapshot = snapshot;
        evaluatedFrames = frames;
        evaluatedAt = now;
        cachedTargets = List.copyOf(result);
        return cachedTargets;
    }

    private boolean sameWindows(List<MonitorSectorAnimationState.ProfileFrame> frames) {
        if (frames.size() != evaluatedFrames.size()) return false;
        for (int i = 0; i < frames.size(); i++) {
            var current = frames.get(i);
            var previous = evaluatedFrames.get(i);
            if (current.profile() != previous.profile()
                    || current.windowEndClientTick() != previous.windowEndClientTick()
                    || !current.activeSectorIndices().equals(previous.activeSectorIndices())
                    || (current.flashStrength() > 0) != (previous.flashStrength() > 0)) return false;
        }
        return true;
    }

    private static final class Marker {
        MonitorTargetData shown;
        double activeUntil;
        double frozenAt = -1;
        double fadeTicks;
        long serverFreezeTick = -1;
        double serverFrozenAt;
        long serverFadeTicks = -1;

        void serverMemory(MonitorTargetData raw, MonitorSnapshotData snapshot, double receivedAt) {
            if (raw.observing()) {
                serverFreezeTick = -1;
                serverFadeTicks = -1;
            } else {
                if (serverFreezeTick != raw.freezeTick()) {
                    serverFreezeTick = raw.freezeTick();
                    serverFrozenAt = receivedAt + raw.freezeTick() - snapshot.observedServerTick();
                    serverFadeTicks = raw.fadeDurationTicks();
                }
            }
        }
    }
}
