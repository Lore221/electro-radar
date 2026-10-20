package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Общая монотонная клиентская фаза визуального окна для каждого радара в составе монитора.
 *
 * <p>Снимок целей применяется целиком до вызова этого класса. Фаза никогда не запускает поиск
 * сущностей и не меняет серверную свежесть: она лишь определяет оформление уже принятых точек.
 * GUI и экран блока получают один и тот же {@link ProfileFrame} из этого состояния.</p>
 */
final class MonitorSectorAnimationState {
    /** Начальная длительность импульса; для короткого окна она ограничивается его периодом. */
    private static final double FULL_FLASH_DURATION_TICKS = 4.0D;
    /** Яркость свежей метки к концу окна после завершения общей вспышки. */
    private static final float FULL_FLASH_REST_TARGET_OPACITY = 0.30F;

    private static final Map<MonitorDisplayKey, Map<RadarVisualProfile.Key, ProfileCycle>> CYCLES = new HashMap<>();

    private MonitorSectorAnimationState() {
    }

    /**
     * Применяет профиль и опубликованное окно только после сборки всех частей снимка.
     * Новая фаза создаётся при смене epoch; повторный sequence, адресное открытие GUI и новая
     * revision того же опубликованного окна могут уточнить возраст и цели, но не возвращают фазу
     * назад и не запускают вспышку повторно. Порядок пакетов по-прежнему задаёт sequence корня.
     */
    static void accept(MonitorDisplayKey key, MonitorSnapshotData previous,
                       MonitorSnapshotData current, double clientTickTime) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (!current.linked() || current.publishedEpoch() <= 0 || current.visualProfiles().isEmpty()) {
            clearDisplay(key, cycles, "unlinked_or_no_profiles", current.publishedEpoch(), clientTickTime);
            return;
        }
        if (cycles == null) {
            cycles = new HashMap<>();
            CYCLES.put(key, cycles);
        }

        List<RadarVisualProfile> profiles = current.visualProfiles().stream()
                .sorted(Comparator.comparing(RadarVisualProfile::radarPosition))
                .toList();
        Set<RadarVisualProfile.Key> currentKeys = new HashSet<>();
        for (RadarVisualProfile profile : profiles) currentKeys.add(profile.key());
        int beforePrune = cycles.size();
        cycles.keySet().removeIf(profileKey -> !currentKeys.contains(profileKey));
        int removedProfiles = beforePrune - cycles.size();
        if (removedProfiles > 0) {
            logStateCleared(key, current.publishedEpoch(), clientTickTime,
                    "reason=profile_removed profiles=" + removedProfiles);
        }

        boolean firstView = previous == null;
        boolean newPublishedEpoch = firstView
                || current.publishedEpoch() != previous.publishedEpoch();
        if (newPublishedEpoch) {
            for (RadarVisualProfile profile : profiles) {
                ProfileCycle oldCycle = cycles.get(profile.key());
                if (oldCycle != null && oldCycle.profile.animation() != profile.animation()) {
                    logModeChanged(key, current, profile, oldCycle.profile.animation(), clientTickTime,
                            appliedWindowAge(current.visualWindowPeriodTicks(),
                                    current.visualWindowRemainingTicks()), true,
                            "reason=new_published_window");
                }
                TargetAssignment assignment = assignTargets(current, profile);
                ProfileCycle previousCycle = oldCycle == null ? null : oldCycle.withoutHistory();
                ProfileCycle cycle = new ProfileCycle(profile, current.publishedEpoch(),
                        clientTickTime, current.visualWindowPeriodTicks(),
                        current.visualWindowRemainingTicks(), clockwiseOrder(profile.sectorCount()),
                        assignment, previousCycle, true);
                cycles.put(profile.key(), cycle);
                logProfileWindow(key, current, cycle, clientTickTime,
                        firstView ? "first_client_view" : "new_published_epoch");
                if (profile.animation() == RadarVisualAnimation.SECTOR_SWEEP) {
                    RadarDiagnostics.log("monitor_visual_cycle_started", null, null,
                            current.publishedEpoch(), (long) clientTickTime,
                            "clock=client displayId=" + current.displayId()
                                    + " radar=" + profile.radarPosition()
                                    + " mode=SECTOR_SWEEP sectors=" + profile.sectorCount()
                                    + " order=" + Arrays.toString(cycle.order)
                                    + " periodTicks=" + cycle.periodTicks
                                    + " remainingTicks=" + current.visualWindowRemainingTicks()
                                    + " phaseAgeTicks=" + cycle.windowAge(clientTickTime));
                } else {
                    logFlashStartedIfActive(key, current, cycle, clientTickTime);
                }
            }
            return;
        }

        // Тот же epoch может принести уточнённую геометрию, цель или остаток окна.
        // Обновляем привязку меток при приёме, но сохраняем уже достигнутую клиентскую фазу.
        // В отличие от первого просмотра, здесь epoch уже был показан: новый профиль или
        // переключение его режима не вправе запускать вторую FULL_FLASH до новой публикации.
        for (RadarVisualProfile profile : profiles) {
            ProfileCycle cycle = cycles.get(profile.key());
            if (cycle == null) {
                cycle = new ProfileCycle(profile, current.publishedEpoch(), clientTickTime,
                        current.visualWindowPeriodTicks(), current.visualWindowRemainingTicks(),
                        clockwiseOrder(profile.sectorCount()), assignTargets(current, profile), null, false);
                cycles.put(profile.key(), cycle);
                logProfileWindow(key, current, cycle, clientTickTime, "profile_joined_current_window");
                if (profile.animation() == RadarVisualAnimation.SECTOR_SWEEP) {
                    RadarDiagnostics.log("monitor_visual_cycle_started", null, null,
                            current.publishedEpoch(), (long) clientTickTime,
                            "clock=client displayId=" + current.displayId()
                                    + " radar=" + profile.radarPosition()
                                    + " mode=SECTOR_SWEEP sectors=" + profile.sectorCount()
                                    + " order=" + Arrays.toString(cycle.order)
                                    + " periodTicks=" + cycle.periodTicks
                                    + " remainingTicks=" + current.visualWindowRemainingTicks()
                                    + " phaseAgeTicks=" + cycle.windowAge(clientTickTime));
                } else {
                    logFlashStartedIfActive(key, current, cycle, clientTickTime);
                }
                continue;
            }

            RadarVisualProfile oldProfile = cycle.profile;
            cycle.reconcileWindowTiming(clientTickTime,
                    current.visualWindowPeriodTicks(), current.visualWindowRemainingTicks());
            if (oldProfile.animation() != profile.animation()) {
                // Режим, появившийся внутри уже известного окна, ждёт следующую публикацию.
                // Сбрасывается допуск, а не таймер: текущая фаза окна продолжает идти монотонно.
                cycle.fullFlashAllowed = false;
                logModeChanged(key, current, profile, oldProfile.animation(), clientTickTime,
                        cycle.windowAge(clientTickTime), cycle.fullFlashAllowed,
                        "reason=profile_updated_in_same_window");
            }
            cycle.profile = profile;
            if (cycle.order.length != profile.sectorCount()) {
                // Меняем число частей в той же фазе окна, не сдвигая её начало.
                cycle.order = clockwiseOrder(profile.sectorCount());
                cycle.lastLoggedSector = -1;
                cycle.completedLogged = false;
            }
            cycle.assignment = assignTargets(current, profile);
            cycle.newTargetCounts = ProfileCycle.countNewTargets(cycle.assignment, cycle.previous);
            if (!oldProfile.equals(profile)) {
                RadarDiagnostics.log("monitor_visual_profile_updated", null, null,
                        current.publishedEpoch(), (long) clientTickTime,
                        "clock=client displayId=" + current.displayId()
                                + " radar=" + profile.radarPosition()
                                + " mode=" + profile.animation()
                                + " sectors=" + profile.sectorCount()
                                + " phaseAgeTicks=" + cycle.windowAge(clientTickTime));
            }
            if (profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                logFlashStartedIfActive(key, current, cycle, clientTickTime);
            }
        }
    }

    /**
     * Возвращает общую визуальную фазу на данный кадр. Секторные события порождаются только для
     * SECTOR_SWEEP; FULL_FLASH передаёт всем рендерам одну силу импульса без деления на клинья.
     */
    static List<ProfileFrame> activeVisuals(MonitorDisplayKey key, float partialTick) {
        Map<RadarVisualProfile.Key, ProfileCycle> cycles = CYCLES.get(key);
        if (cycles == null || cycles.isEmpty() || Minecraft.getInstance().level == null) return List.of();
        double renderTime = MonitorClientState.renderTime(partialTick);
        ArrayList<ProfileFrame> active = new ArrayList<>(cycles.size());
        for (ProfileCycle cycle : cycles.values()) {
            if (cycle.profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                float strength = cycle.flashStrength(renderTime);
                if (cycle.flashStartedLogged && !cycle.flashCompletedLogged
                        && cycle.windowAge(renderTime) >= cycle.flashDurationTicks()) {
                    cycle.flashCompletedLogged = true;
                    RadarDiagnostics.log("monitor_visual_flash_completed", null, null,
                            cycle.epoch, (long) renderTime,
                            "clock=client displayId=" + key.displayId()
                                    + " radar=" + cycle.profile.radarPosition()
                                    + " mode=FULL_FLASH periodTicks=" + cycle.periodTicks
                                    + " phaseAgeTicks=" + cycle.windowAge(renderTime));
                }
                active.add(new ProfileFrame(cycle.profile, -1, strength));
                continue;
            }

            int index = cycle.activeSectorIndex(renderTime);
            if (index >= 0) {
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
                                + " radar=" + cycle.profile.radarPosition()
                                + " mode=SECTOR_SWEEP");
            }
            active.add(new ProfileFrame(cycle.profile, index, 0.0F));
        }
        return List.copyOf(active);
    }

    /**
     * Вычисляет яркость свежей метки по всем областям, где она действительно находится.
     * Для разных радаров выбирается максимум, а не сумма, поэтому перекрывающиеся вспышки не
     * дублируют точку. Цель вне всех визуальных областей сохраняет обычную яркость.
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
            opacity = Math.max(opacity, cycle.targetOpacity(sector, renderTime));
            // Старый sector sweep остаётся видимым до прихода нового сектора, если цель была
            // свежей и в предыдущем окне. У FULL_FLASH все текущие свежие точки обновляются вместе.
            if (previouslyFresh && cycle.profile.animation() == RadarVisualAnimation.SECTOR_SWEEP
                    && cycle.previous != null) {
                Integer previousSector = cycle.previous.assignment.sectors().get(target);
                if (previousSector != null) {
                    opacity = Math.max(opacity,
                            cycle.previous.targetOpacity(previousSector, renderTime));
                }
            }
        }
        return covered ? opacity : 1.0F;
    }

    /** Удаляет фазу при выгрузке корня или смене его клиентской идентичности. */
    static void remove(MonitorDisplayKey key) {
        Map<RadarVisualProfile.Key, ProfileCycle> removed = CYCLES.remove(key);
        if (removed != null && !removed.isEmpty()) {
            logStateCleared(key, -1L, null,
                    "reason=display_removed profiles=" + removed.size());
        }
    }

    /** Сбрасывает клиентские фазы при смене мира и сообщает об очистке только при реальном изменении. */
    static void clear() {
        if (CYCLES.isEmpty()) return;
        int displayCount = CYCLES.size();
        int profileCount = CYCLES.values().stream().mapToInt(Map::size).sum();
        RadarDiagnostics.log("monitor_visual_state_cleared", null, null, null,
                MonitorClientState.clientTickTimeForDiagnostics(),
                "clock=client reason=client_level_changed displays=" + displayCount
                        + " profiles=" + profileCount);
        CYCLES.clear();
    }

    private static void clearDisplay(MonitorDisplayKey key,
                                     Map<RadarVisualProfile.Key, ProfileCycle> cycles,
                                     String reason, long epoch, double clientTickTime) {
        if (cycles == null) return;
        if (cycles.isEmpty()) {
            CYCLES.remove(key);
            return;
        }
        int count = cycles.size();
        CYCLES.remove(key);
        logStateCleared(key, epoch, clientTickTime, "reason=" + reason + " profiles=" + count);
    }

    private static void logStateCleared(MonitorDisplayKey key, Object epoch,
                                        Object clientTickTime, String details) {
        RadarDiagnostics.log("monitor_visual_state_cleared", null, null, epoch, clientTickTime,
                "clock=client displayId=" + key.displayId() + " " + details);
    }

    private static void logProfileWindow(MonitorDisplayKey key, MonitorSnapshotData snapshot,
                                         ProfileCycle cycle, double clientTickTime, String reason) {
        RadarDiagnostics.log("monitor_visual_profile_window", null, null, cycle.epoch,
                (long) clientTickTime,
                "clock=client displayId=" + key.displayId()
                        + " radar=" + cycle.profile.radarPosition()
                        + " mode=" + cycle.profile.animation()
                        + " periodTicks=" + cycle.periodTicks
                        + " remainingTicks=" + snapshot.visualWindowRemainingTicks()
                        + " phaseAgeTicks=" + cycle.windowAge(clientTickTime)
                        + " fullFlashEligible=" + cycle.fullFlashAllowed
                        + " reason=" + reason);
    }

    private static void logModeChanged(MonitorDisplayKey key, MonitorSnapshotData snapshot,
                                       RadarVisualProfile profile, RadarVisualAnimation previousMode,
                                       double clientTickTime, double phaseAge,
                                       boolean fullFlashAllowed, String reason) {
        RadarDiagnostics.log("monitor_visual_mode_changed", null, null,
                snapshot.publishedEpoch(), (long) clientTickTime,
                "clock=client displayId=" + key.displayId()
                        + " radar=" + profile.radarPosition()
                        + " from=" + previousMode + " to=" + profile.animation()
                        + " periodTicks=" + snapshot.visualWindowPeriodTicks()
                        + " remainingTicks=" + snapshot.visualWindowRemainingTicks()
                        + " phaseAgeTicks=" + phaseAge
                        + " fullFlashEligible=" + fullFlashAllowed + " " + reason);
    }

    private static void logFlashStartedIfActive(MonitorDisplayKey key, MonitorSnapshotData snapshot,
                                                ProfileCycle cycle, double clientTickTime) {
        if (!cycle.fullFlashAllowed || cycle.flashStartedLogged || cycle.flashDurationTicks() <= 0.0D
                || cycle.windowAge(clientTickTime) >= cycle.flashDurationTicks()) return;
        cycle.flashStartedLogged = true;
        RadarDiagnostics.log("monitor_visual_flash_started", null, null, cycle.epoch,
                (long) clientTickTime,
                "clock=client displayId=" + key.displayId()
                        + " radar=" + cycle.profile.radarPosition()
                        + " mode=FULL_FLASH periodTicks=" + cycle.periodTicks
                        + " remainingTicks=" + snapshot.visualWindowRemainingTicks()
                        + " phaseAgeTicks=" + cycle.windowAge(clientTickTime)
                        + " flashDurationTicks=" + cycle.flashDurationTicks());
    }

    /** Возраст окна, восстановленный только при приёме полного опубликованного снимка. */
    private static long appliedWindowAge(long periodTicks, long remainingTicks) {
        if (periodTicks <= 0L) return 0L;
        return Math.max(0L, Math.min(periodTicks, periodTicks - remainingTicks));
    }

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

    /** Сохраняет контракт будущих направленных радаров: части идут по часовой стрелке по индексу. */
    private static int[] clockwiseOrder(int sectorCount) {
        int[] indexes = new int[sectorCount];
        for (int i = 0; i < sectorCount; i++) indexes[i] = i;
        return indexes;
    }

    record ProfileFrame(RadarVisualProfile profile, int activeSectorIndex, float flashStrength) {
    }

    private static final class ProfileCycle {
        private RadarVisualProfile profile;
        private final long epoch;
        /** Оценка начала окна на монотонной клиентской шкале; адресный пакет может сдвинуть её только назад. */
        private double windowStartClientTickTime;
        private long periodTicks;
        private int[] order;
        private TargetAssignment assignment;
        private int[] newTargetCounts;
        /** Не более одного предыдущего окна нужно секторной метке для непрерывного хвоста. */
        private final ProfileCycle previous;
        private int lastLoggedSector = -1;
        private boolean completedLogged;
        /** Запрещает повторный импульс профилю, появившемуся или сменившему режим в текущем epoch. */
        private boolean fullFlashAllowed;
        private boolean flashStartedLogged;
        private boolean flashCompletedLogged;

        private ProfileCycle(RadarVisualProfile profile, long epoch, double clientTickTime,
                             long periodTicks, long remainingTicks, int[] order,
                             TargetAssignment assignment, ProfileCycle previous,
                             boolean fullFlashAllowed) {
            this(profile, epoch, clientTickTime - appliedWindowAge(periodTicks, remainingTicks),
                    periodTicks, order, assignment, previous, fullFlashAllowed);
        }

        /** Копирующий путь принимает уже вычисленное начало окна и не делает повторный пересчёт возраста. */
        private ProfileCycle(RadarVisualProfile profile, long epoch, double windowStartClientTickTime,
                             long periodTicks, int[] order,
                             TargetAssignment assignment, ProfileCycle previous,
                             boolean fullFlashAllowed) {
            this.profile = profile;
            this.epoch = epoch;
            this.periodTicks = Math.max(0L, periodTicks);
            this.windowStartClientTickTime = windowStartClientTickTime;
            this.order = order.clone();
            this.assignment = assignment;
            this.previous = previous;
            this.fullFlashAllowed = fullFlashAllowed;
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

        /**
         * При новом адресном сообщении фаза может только догнаться до более старшего возраста.
         * Устаревший остаток не может вернуть окно назад и повторить уже прошедшую вспышку.
         */
        private void reconcileWindowTiming(double now, long reportedPeriod, long remainingTicks) {
            if (reportedPeriod > 0L) periodTicks = reportedPeriod;
            long reportedAge = appliedWindowAge(periodTicks, remainingTicks);
            double reportedStart = now - reportedAge;
            if (reportedStart < windowStartClientTickTime) {
                windowStartClientTickTime = reportedStart;
            }
        }

        private double windowAge(double now) {
            return Math.max(0.0D, now - windowStartClientTickTime);
        }

        /** Мягко затухающий общий импульс; геометрия остаётся неподвижной. */
        private float flashStrength(double now) {
            double duration = flashDurationTicks();
            double age = windowAge(now);
            if (!fullFlashAllowed || duration <= 0.0D || age < 0.0D || age >= duration) return 0.0F;
            double remainingFraction = 1.0D - age / duration;
            return (float) (remainingFraction * remainingFraction);
        }

        /** Свежая метка яркая во время импульса, затем спокойно снижается до остаточной читаемости. */
        private float fullFlashTargetOpacity(double now) {
            if (periodTicks <= 0L) return FULL_FLASH_REST_TARGET_OPACITY;
            if (!fullFlashAllowed) return FULL_FLASH_REST_TARGET_OPACITY;
            double age = windowAge(now);
            double flashDuration = flashDurationTicks();
            if (age <= flashDuration) return 1.0F;
            if (age >= periodTicks || periodTicks <= flashDuration) {
                return FULL_FLASH_REST_TARGET_OPACITY;
            }
            double fadeProgress = (age - flashDuration) / (periodTicks - flashDuration);
            return (float) (1.0D - fadeProgress * (1.0D - FULL_FLASH_REST_TARGET_OPACITY));
        }

        private double flashDurationTicks() {
            return Math.min(FULL_FLASH_DURATION_TICKS, periodTicks);
        }

        private float targetOpacity(int sectorIndex, double now) {
            if (profile.animation() == RadarVisualAnimation.FULL_FLASH) {
                return fullFlashTargetOpacity(now);
            }
            return sectorOpacity(sectorIndex, now);
        }

        /** Момент входа части в последовательный sweep. */
        private double entryTime(int sectorIndex) {
            for (int slot = 0; slot < order.length; slot++) {
                if (order[slot] == sectorIndex) {
                    return windowStartClientTickTime + slot * (double) periodTicks / order.length;
                }
            }
            return Double.POSITIVE_INFINITY;
        }

        private float sectorOpacity(int sectorIndex, double now) {
            double entry = entryTime(sectorIndex);
            if (now < entry) return 0.0F;
            double exit = exitTime(sectorIndex);
            if (now <= exit) return 1.0F;
            if (periodTicks <= 0L) return 0.0F;
            return (float) Math.clamp(1.0D - (now - exit) / periodTicks, 0.0D, 1.0D);
        }

        /** Граница после части: до неё цель сохраняет полную яркость, затем начинается затухание. */
        private double exitTime(int sectorIndex) {
            for (int slot = 0; slot < order.length; slot++) {
                if (order[slot] == sectorIndex) {
                    return windowStartClientTickTime + (slot + 1.0D) * (double) periodTicks / order.length;
                }
            }
            return Double.NEGATIVE_INFINITY;
        }

        private int activeSectorIndex(double renderTime) {
            double elapsed = Math.max(0.0D, renderTime - windowStartClientTickTime);
            if (periodTicks <= 0L || elapsed >= periodTicks || order.length == 0) return -1;
            int slot = Math.min(order.length - 1,
                    (int) Math.floor(elapsed * order.length / periodTicks));
            return order[slot];
        }

        /** Копирует только один прошлый проход, не создавая неограниченную историю снимков. */
        private ProfileCycle withoutHistory() {
            return new ProfileCycle(profile, epoch, windowStartClientTickTime,
                    periodTicks, order, assignment, null, fullFlashAllowed);
        }
    }

    private record TargetAssignment(int[] counts, Map<TargetRef, Integer> sectors) {
    }
}
