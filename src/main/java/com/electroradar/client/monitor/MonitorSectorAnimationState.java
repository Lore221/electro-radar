package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.electroradar.config.MonitorSweepConfig;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.visual.RadarSectorOrder;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualFadeStyle;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Клиентские часы обхода. Серверная фаза сообщает готовность, но не переключает лучи. */
final class MonitorSectorAnimationState {
    private static final Map<MonitorDisplayKey, Map<RadarVisualProfile.Key, ProfileCycle>> CYCLES = new HashMap<>();

    private MonitorSectorAnimationState() { }

    static void accept(MonitorDisplayKey key, MonitorSnapshotData snapshot, double now) {
        if (!snapshot.linked()) { remove(key); return; }
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.computeIfAbsent(key, ignored -> new HashMap<>());
        cycles.keySet().removeIf(profileKey -> snapshot.visualProfiles().stream()
                .noneMatch(profile -> profile.key().equals(profileKey)));
        // Общая эпоха мировых часов не зависит от состава сети и времени доставки пакетов.
        double networkOffset = 0.0D;
        for (RadarVisualProfile source : snapshot.visualProfiles()) {
            boolean clientSweep = source.animation() == RadarVisualAnimation.SECTOR_SWEEP
                    && source.inactiveSectorFadeStyle() != RadarVisualFadeStyle.NONE;
            var settings = MonitorSweepConfig.sweep(source.inactiveSectorFadeStyle());
            double sectorFade = settings.sectorFadeTicks();
            ProfileCycle cycle = cycles.get(source.key());
            if (cycle == null || !cycle.source.equals(source) || clientSweep && (!cycle.settings.equals(settings)
                    || cycle.sectorFade != sectorFade || cycle.startedAt != networkOffset)) {
                cycle = new ProfileCycle(source, settings, sectorFade, networkOffset);
                cycles.put(source.key(), cycle);
            }
            // Общий FULL_FLASH сохраняет серверный момент вспышки. Стационарные
            // радары используют независимый клиентский sweep.
            for (MonitorVisualPhase phase : snapshot.visualPhases()) {
                // Источник сравнивается по endpoint: у радара на структуре позиция хранения не мировая.
                if (phase.radarEndpoint().equals(source.source()) && !phase.equals(cycle.flashPhase)) {
                    cycle.flashPhase = phase;
                    cycle.flashStart = now + phase.windowStartTick() - snapshot.observedServerTick();
                }
            }
        }
    }

    static List<ProfileFrame> activeVisuals(MonitorDisplayKey key, float partialTick) {
        return visualsAt(key, MonitorClientState.renderTime(partialTick));
    }

    static List<ProfileFrame> visualsAt(MonitorDisplayKey key, double now) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null) return List.of();
        // Reload применяется и в тихой сети, без ожидания следующего пакета.
        MonitorSnapshotData snapshot = MonitorClientState.snapshot(key);
        boolean settingsChanged = cycles.values().stream().anyMatch(cycle ->
                cycle.source.animation() == RadarVisualAnimation.SECTOR_SWEEP
                        && cycle.source.inactiveSectorFadeStyle() != RadarVisualFadeStyle.NONE
                        && !cycle.settings.equals(MonitorSweepConfig.sweep(cycle.source.inactiveSectorFadeStyle())));
        if (snapshot != null && settingsChanged) accept(key, snapshot, now);
        List<ProfileFrame> frames = new ArrayList<>(cycles.size());
        for (ProfileCycle cycle : cycles.values()) frames.add(cycle.frame(now));
        return List.copyOf(frames);
    }

    static void remove(MonitorDisplayKey key) { CYCLES.remove(key); }
    static void clear() { CYCLES.clear(); }

    record ProfileFrame(RadarVisualProfile profile, List<Integer> activeSectorIndices,
                        Map<Integer, Float> fadingSectorStrengths, float flashStrength,
                        double windowEndClientTick) {
        ProfileFrame {
            activeSectorIndices = List.copyOf(activeSectorIndices);
            fadingSectorStrengths = Map.copyOf(fadingSectorStrengths);
        }
        float sectorStrength(int index) {
            return activeSectorIndices.contains(index) ? 1.0F : fadingSectorStrengths.getOrDefault(index, 0.0F);
        }
    }

    private static final class ProfileCycle {
        final RadarVisualProfile source;
        final RadarVisualProfile profile;
        final MonitorSweepConfig.SweepParameters settings;
        final double sectorFade;
        final double startedAt;
        final List<int[]> orders = new ArrayList<>();
        final List<int[]> inverseOrders = new ArrayList<>();
        MonitorVisualPhase flashPhase;
        double flashStart;

        ProfileCycle(RadarVisualProfile source, MonitorSweepConfig.SweepParameters settings, double sectorFade, double networkOffset) {
            this.source = source;
            this.settings = settings;
            this.sectorFade = sectorFade;
            this.startedAt = networkOffset;
            this.profile = source.animation() == RadarVisualAnimation.SECTOR_SWEEP
                    && source.inactiveSectorFadeStyle() != RadarVisualFadeStyle.NONE
                    ? MonitorSweepGeometry.partition(source, settings.sectors()) : source;
            int beams = Math.min(settings.beams(), profile.sectorCount());
            for (int group = 0; group < beams; group++) {
                int count = profile.sectorCount() * (group + 1) / beams - profile.sectorCount() * group / beams;
                int variant = 1 + Math.floorMod(source.sectorOrderVariant() - 1 + group, RadarSectorOrder.VARIANT_COUNT);
                int[] order = RadarSectorOrder.permutation(count, variant);
                if (settings.order() == MonitorSweepConfig.SweepOrder.SEQUENTIAL) {
                    for (int i = 0; i < count; i++) order[i] = i;
                }
                int[] inverse = new int[count];
                for (int i = 0; i < count; i++) inverse[order[i]] = i;
                orders.add(order);
                inverseOrders.add(inverse);
            }
        }

        ProfileFrame frame(double now) {
            if (profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                double age = Math.max(0, now - flashStart);
                double duration = flashPhase == null ? 0 : Math.min(6.0D,
                        flashPhase.windowEndTick() - flashPhase.windowStartTick());
                float strength = duration <= 0 ? 0 : (float) Math.pow(Math.max(0, 1 - age / duration), 2);
                return new ProfileFrame(profile, List.of(), Map.of(), strength, flashStart + duration);
            }
            if (source.inactiveSectorFadeStyle() == RadarVisualFadeStyle.NONE) {
                // Общий SPI произвольных источников сохраняет фактический набор зон.
                // Новая клиентская нарезка относится к штатным PANEL/OVERVIEW.
                return new ProfileFrame(profile, flashPhase == null ? List.of() : flashPhase.activeSectorIndices(),
                        Map.of(), 0, flashPhase == null ? now : flashStart
                                + flashPhase.windowEndTick() - flashPhase.windowStartTick());
            }
            long step = (long) Math.floor(Math.max(0, now - startedAt) / settings.activeTicks());
            List<Integer> active = new ArrayList<>(orders.size());
            Map<Integer, Float> fading = new HashMap<>();
            for (int group = 0; group < orders.size(); group++) {
                int[] order = orders.get(group), inverse = inverseOrders.get(group);
                int base = profile.sectorCount() * group / orders.size();
                int current = order[(int) (step % order.length)];
                active.add(base + current);
                if (sectorFade <= 0) continue;
                for (int index = 0; index < order.length; index++) {
                    if (index == current) continue;
                    long lastStep = step - Math.floorMod(step - inverse[index], (long) order.length);
                    if (lastStep < 0) continue;
                    double stoppedAt = startedAt + (lastStep + 1) * settings.activeTicks();
                    float strength = (float) Math.clamp(1 - (now - stoppedAt) / sectorFade, 0, 1);
                    if (strength > 0) fading.put(base + index, strength);
                }
            }
            return new ProfileFrame(profile, active, fading, 0, startedAt + (step + 1) * settings.activeTicks());
        }
    }
}
