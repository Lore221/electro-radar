package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorVisualPhase;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Отображает серверный слот и геометрию, не выводя расписание из момента прихода пакета. */
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
            if (phase == null) continue;
            ProfileCycle old = cycles.get(profile.key());
            // Новое посещение — другое начало окна; новая геометрия — другой профиль.
            boolean newSlot = old == null || old.phase.windowStartTick() != phase.windowStartTick()
                    || !old.profile.equals(profile);
            double mappedWindowStart = clientTickTime + phase.windowStartTick() - current.observedServerTick();
            // Повторный sample не сдвигает привязку начала окна к клиентскому времени.
            ProfileCycle cycle = new ProfileCycle(profile, phase,
                    newSlot ? mappedWindowStart : old.windowStartClientTick);
            cycles.put(profile.key(), cycle);
            if (newSlot && RadarDiagnostics.visualDiagnosticsEnabled()) {
                RadarDiagnostics.log("monitor_visual_server_slot", null, null, null, (long) clientTickTime,
                        "displayId=" + key.displayId() + " radar=" + profile.radarPosition()
                                + " sector=" + phase.activeSectorIndex() + " window=[" + phase.windowStartTick()
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
            if (cycle.profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                active.add(new ProfileFrame(cycle.profile, -1, cycle.flashStrength(renderTime)));
            } else {
                active.add(new ProfileFrame(cycle.profile, cycle.phase.activeSectorIndex(), 0.0F));
            }
        }
        return List.copyOf(active);
    }

    static void remove(MonitorDisplayKey key) {
        CYCLES.remove(key);
    }

    static void clear() {
        CYCLES.clear();
    }

    record ProfileFrame(RadarVisualProfile profile, int activeSectorIndex, float flashStrength) { }

    private static final class ProfileCycle {
        final RadarVisualProfile profile;
        final MonitorVisualPhase phase;
        final double windowStartClientTick;
        final long periodTicks;

        ProfileCycle(RadarVisualProfile profile, MonitorVisualPhase phase,
                     double windowStartClientTick) {
            this.profile = profile;
            this.phase = phase;
            this.windowStartClientTick = windowStartClientTick;
            this.periodTicks = phase.windowEndTick() - phase.windowStartTick();
        }

        double windowAge(double now) { return Math.max(0.0D, now - windowStartClientTick); }

        float flashStrength(double now) {
            double duration = Math.min(FULL_FLASH_DURATION_TICKS, periodTicks);
            double age = windowAge(now);
            if (duration <= 0.0D || age >= duration) return 0.0F;
            double remaining = 1.0D - age / duration;
            return (float) (remaining * remaining);
        }
    }
}
