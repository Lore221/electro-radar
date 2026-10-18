package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Короткое клиентское состояние визуального прохода для каждого монитора и радара.
 *
 * <p>Сервер сообщает опубликованный epoch и остаток его сетевого окна; этот класс использует
 * только монотонный счётчик клиентских тиков для выбора кадра. Подсветка не управляет
 * фактическим поиском: снимок целей уже принят при начале прохода, а здесь определяется
 * только момент появления каждой метки.</p>
 */
final class MonitorSectorAnimationState {
    private static final Map<MonitorDisplayKey, Map<RadarVisualProfile.Key, ProfileCycle>> CYCLES = new HashMap<>();

    private MonitorSectorAnimationState() {
    }

    /**
     * Применяет только изменение состояния снимка; метод вызывается после сборки всех его частей.
     * Совпавший epoch может обновить геометрию, но не изменяет фазу уже начатого круга.
     */
    static void accept(MonitorDisplayKey key, MonitorSnapshotData previous,
                       MonitorSnapshotData current, double clientTickTime) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.computeIfAbsent(key,
                ignored -> new HashMap<>());
        if (!current.linked() || current.publishedEpoch() <= 0 || current.visualProfiles().isEmpty()) {
            cycles.clear();
            return;
        }

        Map<RadarVisualProfile.Key, RadarVisualProfile> profiles = profileMap(current.visualProfiles());
        cycles.keySet().removeIf(profileKey -> !profiles.containsKey(profileKey));
        boolean firstView = previous == null;
        boolean newPublishedEpoch = firstView
                || current.publishedEpoch() != previous.publishedEpoch();

        if (newPublishedEpoch) {
            for (RadarVisualProfile profile : profiles.values()) {
                int[] order = clockwiseOrder(profile.sectorCount());
                TargetAssignment assignment = assignTargets(current, profile);
                ProfileCycle oldCycle = cycles.get(profile.key());
                ProfileCycle nextCycle = new ProfileCycle(profile, current.publishedEpoch(),
                        clientTickTime, current.visualWindowRemainingTicks(),
                        current.visualWindowPeriodTicks(), order, assignment,
                        oldCycle == null ? null : oldCycle.withoutHistory());
                cycles.put(profile.key(), nextCycle);
                RadarDiagnostics.log("monitor_visual_cycle_started", null, null,
                        current.publishedEpoch(), (long) clientTickTime,
                        "clock=client displayId=" + current.displayId()
                                + " radar=" + profile.radarPosition()
                                + " sectors=" + profile.sectorCount() + " order=" + Arrays.toString(order)
                                + " periodTicks=" + current.visualWindowPeriodTicks()
                                + " remainingTicks=" + current.visualWindowRemainingTicks());
            }
            return;
        }

        // Адресный снимок того же epoch не меняет уже начатую фазу. Поворот или подключение
        // радара может изменить профиль; прежний круг сохраняем, пока число частей то же.
        for (RadarVisualProfile profile : profiles.values()) {
            ProfileCycle cycle = cycles.get(profile.key());
            if (cycle == null || cycle.profile.sectorCount() != profile.sectorCount()) {
                cycles.put(profile.key(), new ProfileCycle(profile, current.publishedEpoch(),
                        clientTickTime, current.visualWindowRemainingTicks(),
                        current.visualWindowPeriodTicks(), clockwiseOrder(profile.sectorCount()),
                        assignTargets(current, profile), null));
                RadarDiagnostics.log("monitor_visual_profile_restarted", null, null,
                        current.publishedEpoch(), (long) clientTickTime,
                        "clock=client displayId=" + current.displayId()
                                + " radar=" + profile.radarPosition()
                                + " sectors=" + profile.sectorCount());
                continue;
            }
            if (!cycle.profile.equals(profile)) {
                cycle.profile = profile;
                cycle.assignment = assignTargets(current, profile);
                cycle.newTargetCounts = ProfileCycle.countNewTargets(cycle.assignment, cycle.previous);
                RadarDiagnostics.log("monitor_visual_profile_updated", null, null,
                        current.publishedEpoch(), (long) clientTickTime,
                        "clock=client displayId=" + current.displayId()
                                + " radar=" + profile.radarPosition()
                                + " sectors=" + profile.sectorCount());
            }
        }
    }

    /** Возвращает неизменяемый список текущих активных частей для одного общего клиентского кадра. */
    static List<ActiveSector> activeSectors(MonitorDisplayKey key, float partialTick) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null || cycles.isEmpty()) return List.of();
        if (Minecraft.getInstance().level == null) return List.of();
        double renderTime = MonitorClientState.renderTime(partialTick);
        ArrayList<ActiveSector> active = new ArrayList<>(cycles.size());
        for (ProfileCycle cycle : cycles.values()) {
            int index = cycle.activeIndex(renderTime);
            if (index >= 0) {
                active.add(new ActiveSector(cycle.profile, index));
                if (cycle.lastLoggedSector != index) {
                    cycle.lastLoggedSector = index;
                    RadarDiagnostics.log("monitor_visual_sector_activated", null, null,
                            cycle.epoch, (long) renderTime,
                            "clock=client displayId=" + key.displayId()
                                    + " radar=" + cycle.profile.radarPosition()
                                    + " sector=" + index + " freshTargetsInSector="
                                    + cycle.assignment.counts()[index]
                                    + " newTargetsInSector=" + cycle.newTargetCounts[index]);
                }
            } else if (!cycle.completedLogged && cycle.lastLoggedSector >= 0) {
                cycle.completedLogged = true;
                RadarDiagnostics.log("monitor_visual_cycle_completed", null, null,
                        cycle.epoch, (long) renderTime,
                        "clock=client displayId=" + key.displayId()
                                + " radar=" + cycle.profile.radarPosition());
            }
        }
        return List.copyOf(active);
    }

    /**
     * Новая метка скрыта до входа подсветки в её сектор. После выхода она плавно гаснет.
     * Подтверждённая в предыдущем снимке метка сохраняет хвост старого прохода до нового
     * посещения сектора; это исключает общее мигание всех известных целей на границе окна.
     */
    static float targetOpacity(MonitorDisplayKey key, TargetRef target, double renderTime,
                               boolean previouslyFresh) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null || cycles.isEmpty()) return 1.0F;
        float opacity = 0.0F;
        boolean covered = false;
        for (ProfileCycle cycle : cycles.values()) {
            Integer sector = cycle.assignment.sectors().get(target);
            if (sector == null) continue;
            covered = true;
            opacity = Math.max(opacity, cycle.opacity(sector, renderTime));
            if (previouslyFresh && cycle.previous != null) {
                Integer previousSector = cycle.previous.assignment.sectors().get(target);
                if (previousSector != null) {
                    opacity = Math.max(opacity, cycle.previous.opacity(previousSector, renderTime));
                }
            }
        }
        return covered ? opacity : 1.0F;
    }

    /**
     * Сохраняет момент начала уже идущего затухания, когда сервер перестал подтверждать цель.
     * Помнимая точка продолжит прежнюю кривую и не вспыхнет при получении нового epoch.
     */
    static double fadeStartForRemembered(MonitorDisplayKey key, double x, double z,
                                         double fallbackClientTickTime) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null || cycles.isEmpty()) return fallbackClientTickTime;
        double latestExit = latestSectorExit(cycles, x, z, fallbackClientTickTime);
        return Double.isFinite(latestExit) ? latestExit : fallbackClientTickTime;
    }

    private static double latestSectorExit(Map<RadarVisualProfile.Key, ProfileCycle> cycles,
                                           double x, double z, double now) {
        double latest = Double.NEGATIVE_INFINITY;
        for (ProfileCycle cycle : cycles.values()) {
            int sector = RadarVisualGeometry.sectorIndex(cycle.profile, x, z);
            if (sector >= 0 && cycle.exitTime(sector) <= now) {
                latest = Math.max(latest, cycle.exitTime(sector));
            }
            if (cycle.previous != null) {
                int oldSector = RadarVisualGeometry.sectorIndex(cycle.previous.profile, x, z);
                if (oldSector >= 0 && cycle.previous.exitTime(oldSector) <= now) {
                    latest = Math.max(latest, cycle.previous.exitTime(oldSector));
                }
            }
        }
        return latest;
    }

    /** Удаляет фазу при смене мира, загрузки корня или исчезновении его клиентского снимка. */
    static void remove(MonitorDisplayKey key) {
        CYCLES.remove(key);
    }

    /** Сбрасывает все фазы вместе с клиентским миром. */
    static void clear() {
        CYCLES.clear();
    }

    private static Map<RadarVisualProfile.Key, RadarVisualProfile> profileMap(List<RadarVisualProfile> profiles) {
        Map<RadarVisualProfile.Key, RadarVisualProfile> result = new HashMap<>();
        for (RadarVisualProfile profile : profiles) result.put(profile.key(), profile);
        return result;
    }

    /** Приём снимка — единственное место дорогой геометрической проверки свежих точек. */
    private static TargetAssignment assignTargets(MonitorSnapshotData snapshot,
                                                  RadarVisualProfile profile) {
        int[] counts = new int[profile.sectorCount()];
        Map<TargetRef, Integer> sectors = new HashMap<>();
        for (MonitorTargetData target : snapshot.targets()) {
            if (!target.target().dimension().location().equals(snapshot.dimension())
                    || !target.detectedThisWindow() || !target.hasPosition()
                    || sectors.containsKey(target.target())) continue;
            int sector = RadarVisualGeometry.sectorIndex(profile, target.x(), target.z());
            if (sector >= 0) {
                sectors.put(target.target(), sector);
                counts[sector]++;
            }
        }
        return new TargetAssignment(counts, Map.copyOf(sectors));
    }

    /** Один и тот же обход по часовой стрелке для каждого окна, независимо от числа целей. */
    private static int[] clockwiseOrder(int sectorCount) {
        int[] indexes = new int[sectorCount];
        for (int i = 0; i < sectorCount; i++) indexes[i] = i;
        return indexes;
    }

    record ActiveSector(RadarVisualProfile profile, int sectorIndex) {
    }

    private record TargetAssignment(int[] counts, Map<TargetRef, Integer> sectors) {
    }

    private static final class ProfileCycle {
        private RadarVisualProfile profile;
        private final long epoch;
        private final double startClientTickTime;
        private final long periodTicks;
        private final double durationTicks;
        private final int[] order;
        private TargetAssignment assignment;
        private int[] newTargetCounts;
        private final ProfileCycle previous;
        private int lastLoggedSector = -1;
        private boolean completedLogged;

        private ProfileCycle(RadarVisualProfile profile, long epoch, double startClientTickTime,
                             long durationTicks, long periodTicks, int[] order,
                             TargetAssignment assignment,
                             ProfileCycle previous) {
            this.profile = profile;
            this.epoch = epoch;
            this.startClientTickTime = startClientTickTime;
            this.periodTicks = periodTicks;
            this.durationTicks = Math.max(0L, durationTicks);
            this.order = order.clone();
            this.assignment = assignment;
            this.previous = previous;
            this.newTargetCounts = countNewTargets(assignment, previous);
        }

        private static int[] countNewTargets(TargetAssignment assignment, ProfileCycle previous) {
            int[] counts = new int[assignment.counts().length];
            for (Map.Entry<TargetRef, Integer> entry : assignment.sectors().entrySet()) {
                if (previous == null || !previous.assignment.sectors().containsKey(entry.getKey())) {
                    counts[entry.getValue()]++;
                }
            }
            return counts;
        }

        /** Момент входа части в визуальный проход; до него новые данные ещё не показываются. */
        private double entryTime(int sectorIndex) {
            for (int slot = 0; slot < order.length; slot++) {
                if (order[slot] == sectorIndex) {
                    return startClientTickTime + slot * durationTicks / order.length;
                }
            }
            return Double.POSITIVE_INFINITY;
        }

        private float opacity(int sectorIndex, double now) {
            double entry = entryTime(sectorIndex);
            if (now < entry) return 0.0F;
            double exit = exitTime(sectorIndex);
            if (now <= exit) return 1.0F;
            if (periodTicks <= 0L) return 0.0F;
            return (float) Math.clamp(1.0D - (now - exit) / periodTicks, 0.0D, 1.0D);
        }

        /** Храним только один прошлый круг, иначе цепочка снимков росла бы без ограничения. */
        private ProfileCycle withoutHistory() {
            return new ProfileCycle(profile, epoch, startClientTickTime, (long) durationTicks,
                    periodTicks, order, assignment, null);
        }

        /** Граница после сектора, а не его начало: до неё точка сохраняет полную яркость. */
        private double exitTime(int sectorIndex) {
            for (int slot = 0; slot < order.length; slot++) {
                if (order[slot] == sectorIndex) {
                    return startClientTickTime + (slot + 1.0D) * durationTicks / order.length;
                }
            }
            return Double.NEGATIVE_INFINITY;
        }

        private int activeIndex(double renderTime) {
            double elapsed = Math.max(0.0D, renderTime - startClientTickTime);
            if (durationTicks <= 0.0D || elapsed >= durationTicks || order.length == 0) return -1;
            int slot = Math.min(order.length - 1,
                    (int) Math.floor(elapsed * order.length / durationTicks));
            return order[slot];
        }
    }
}
