package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Берёт активные зоны из серверной фазы и хранит отдельный визуальный след выключенных зон. */
final class MonitorSectorAnimationState {
    private static final double FULL_FLASH_DURATION_TICKS = 6.0D;
    private static final Map<MonitorDisplayKey, Map<RadarVisualProfile.Key, ProfileCycle>> CYCLES = new HashMap<>();

    private MonitorSectorAnimationState() { }

    static void accept(MonitorDisplayKey key, MonitorSnapshotData current, double clientTickTime) {
        if (!current.linked() || current.visualProfiles().isEmpty()) {
            remove(key);
            return;
        }
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.computeIfAbsent(key, ignored -> new HashMap<>());
        Map<net.minecraft.core.BlockPos, MonitorVisualPhase> phases = new HashMap<>();
        for (MonitorVisualPhase phase : current.visualPhases()) phases.put(phase.radarPosition(), phase);
        Set<RadarVisualProfile.Key> activeKeys = new HashSet<>();
        for (RadarVisualProfile profile : current.visualProfiles()) activeKeys.add(profile.key());
        cycles.keySet().removeIf(profileKey -> !activeKeys.contains(profileKey));

        for (RadarVisualProfile profile : current.visualProfiles()) {
            MonitorVisualPhase phase = phases.get(profile.radarPosition());
            ProfileCycle old = cycles.get(profile.key());
            // След старой геометрии нельзя переносить на новые индексы того же источника.
            if (old != null && !old.profile.equals(profile)) old = null;
            if (phase == null && old == null) {
                cycles.remove(profile.key());
                continue;
            }
            boolean samePhase = old != null && phase != null && phase.equals(old.phase);
            // Повторный sample не сдвигает привязку начала окна к клиентскому времени.
            double windowStart = phase == null ? 0.0D : samePhase ? old.windowStartClientTick
                    : clientTickTime + phase.windowStartTick() - current.observedServerTick();
            ProfileCycle cycle = new ProfileCycle(profile, phase, windowStart, old,
                    clientTickTime, current.observedServerTick());
            cycles.put(profile.key(), cycle);
            if (!samePhase && phase != null && RadarDiagnostics.visualDiagnosticsEnabled()) {
                RadarDiagnostics.log("monitor_visual_server_slot", null, null, null, (long) clientTickTime,
                        "displayId=" + key.displayId() + " radar=" + profile.radarPosition()
                                + " sectors=" + phase.activeSectorIndices() + " window=[" + phase.windowStartTick()
                                + "," + phase.windowEndTick() + ")");
            }
        }
    }

    static List<ProfileFrame> activeVisuals(MonitorDisplayKey key, float partialTick) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null || cycles.isEmpty() || Minecraft.getInstance().level == null) return List.of();
        double renderTime = MonitorClientState.renderTime(partialTick);
        ArrayList<ProfileFrame> active = new ArrayList<>(cycles.size());
        for (ProfileCycle cycle : cycles.values()) {
            active.add(cycle.frame(renderTime));
        }
        return List.copyOf(active);
    }

    static void remove(MonitorDisplayKey key) {
        CYCLES.remove(key);
    }

    static void clear() {
        CYCLES.clear();
    }

    record ProfileFrame(RadarVisualProfile profile, List<Integer> activeSectorIndices,
                        Map<Integer, Float> fadingSectorStrengths, float flashStrength) {
        ProfileFrame {
            activeSectorIndices = List.copyOf(activeSectorIndices);
            fadingSectorStrengths = Map.copyOf(fadingSectorStrengths);
        }

        float sectorStrength(int sectorIndex) {
            return activeSectorIndices.contains(sectorIndex) ? 1.0F
                    : fadingSectorStrengths.getOrDefault(sectorIndex, 0.0F);
        }
    }

    private static final class ProfileCycle {
        final RadarVisualProfile profile;
        @Nullable
        final MonitorVisualPhase phase;
        final double windowStartClientTick;
        final long periodTicks;
        final Map<Integer, SectorFade> fadingSectors = new HashMap<>();

        ProfileCycle(RadarVisualProfile profile, @Nullable MonitorVisualPhase phase,
                     double windowStartClientTick, @Nullable ProfileCycle old,
                     double receivedAt, long observedServerTick) {
            this.profile = profile;
            this.phase = phase;
            this.windowStartClientTick = windowStartClientTick;
            this.periodTicks = phase == null ? 0L : phase.windowEndTick() - phase.windowStartTick();
            if (old != null && profile.animation() == RadarVisualAnimation.SECTOR_SWEEP) {
                fadingSectors.putAll(old.fadingSectors);
                if (old.phase != null) {
                    // Используем серверный конец посещения в шкале текущего снимка.
                    // Медленный сервер не должен гасить ещё активную зону по часам клиента.
                    double stoppedAt = receivedAt + old.phase.windowEndTick() - observedServerTick;
                    // Исчезновение зоны внутри того же посещения — частичный отказ, а не
                    // конец всего окна. Наблюдаем его по пришедшему снимку.
                    if (phase == null || phase.windowStartTick() == old.phase.windowStartTick()) {
                        stoppedAt = Math.min(stoppedAt, receivedAt);
                    }
                    for (int sectorIndex : old.phase.activeSectorIndices()) {
                        if (phase == null || !phase.activeSectorIndices().contains(sectorIndex)) {
                            fadingSectors.put(sectorIndex, new SectorFade(stoppedAt, profile.inactiveSectorFadeTicks()));
                        }
                    }
                }
            }
            // Повторная активация всегда яркая; повторные пакеты не перезапускают старые следы.
            if (phase != null) phase.activeSectorIndices().forEach(fadingSectors::remove);
            fadingSectors.values().removeIf(fade -> fade.strength(receivedAt) <= 0.0F);
        }

        double windowAge(double now) { return Math.max(0.0D, now - windowStartClientTick); }

        ProfileFrame frame(double now) {
            if (profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                return new ProfileFrame(profile, List.of(), Map.of(), flashStrength(now));
            }
            List<Integer> active = phase == null ? List.of() : phase.activeSectorIndices();
            Map<Integer, Float> fading = new HashMap<>();
            for (Map.Entry<Integer, SectorFade> entry : fadingSectors.entrySet()) {
                float strength = entry.getValue().strength(now);
                if (strength > 0.0F) fading.put(entry.getKey(), strength);
            }
            return new ProfileFrame(profile, active, fading, 0.0F);
        }

        float flashStrength(double now) {
            if (phase == null) return 0.0F;
            double duration = Math.min(FULL_FLASH_DURATION_TICKS, periodTicks);
            double age = windowAge(now);
            if (duration <= 0.0D || age >= duration) return 0.0F;
            double remaining = 1.0D - age / duration;
            return (float) (remaining * remaining);
        }
    }

    private record SectorFade(double startClientTick, int durationTicks) {
        float strength(double now) {
            if (durationTicks <= 0) return 0.0F;
            double age = Math.max(0.0D, now - startClientTick);
            return (float) Math.clamp(1.0D - age / durationTicks, 0.0D, 1.0D);
        }
    }
}
