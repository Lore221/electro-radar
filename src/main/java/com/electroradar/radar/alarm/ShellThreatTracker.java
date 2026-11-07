package com.electroradar.radar.alarm;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.electroradar.cannon.intercept.HostileShell;

/**
 * Кэш принадлежит одному Alarm. Ни Entity, ни сетевые права, ни UUID после потери доступа не хранит.
 *
 * <p>Прогноз сделан против зоны в момент расчёта. Зона на движущейся структуре меняет позу каждый
 * тик, поэтому прогноз переиспользуется, пока зона идёт по его равномерной экстраполяции
 * ({@link ZoneVolume#continues}); иначе он пересчитывается в пределах бюджета.</p>
 */
public final class ShellThreatTracker {
    public static final int PREDICTION_LIMIT = 4_096;
    public static final int PREDICTIONS_PER_TICK = 8;
    private static final double POSITION_EPSILON = 1.0E-4D;
    private static final double VELOCITY_EPSILON = 1.0E-5D;
    /** Допуск движения любой вершины расширенной зоны относительно экстраполяции: четверть блока. */
    private static final double ZONE_DISTANCE_EPSILON = 0.25D;
    private static final double ZONE_SPEED_EPSILON = 0.02D;

    private final Map<UUID, Entry> entries = new HashMap<>();
    private Map<UUID, Integer> dangerous = Map.of();
    private ProtectionBox zone;
    private int incomplete;
    private int predictionCount;

    public Set<UUID> dangerousProjectiles() { return dangerous.keySet(); }
    /** Опасные снаряды и тики до первого пересечения зоны: 0 — уже в зоне или входит в этом ходе. */
    public Map<UUID, Integer> ticksToDanger() { return dangerous; }
    public int incompleteCount() { return incomplete; }
    /** Диагностический счётчик тяжёлых расчётов, не увеличивается при обычном полёте. */
    public int predictionCount() { return predictionCount; }

    public void clear() {
        entries.clear();
        dangerous = Map.of();
        zone = null;
        incomplete = 0;
    }

    /** Вызывающий уже отобрал только разрешённые текущей сетью живые большие снаряды. */
    public void refresh(Collection<Observation> observations, ProtectionBox nextZone) {
        refresh(observations, nextZone, Set.of());
    }

    /** Текущие пересечения из того же разрешённого набора не требуют поддержки прогноза. */
    public void refresh(Collection<Observation> observations, ProtectionBox nextZone, Set<UUID> currentThreats) {
        refresh(observations, nextZone, currentThreats, null);
    }

    /** Наблюдатель вызывается только при запросе диагностики и не участвует в принятии решения. */
    public void refresh(Collection<Observation> observations, ProtectionBox nextZone, Set<UUID> currentThreats,
                        Consumer<Decision> diagnostics) {
        refresh(observations, ZoneVolume.fixed(nextZone), currentThreats, diagnostics);
    }

    /** Зона может быть повёрнута и двигаться вместе со структурой, на которой она выбрана. */
    public void refresh(Collection<Observation> observations, ZoneVolume nextZone, Set<UUID> currentThreats,
                        Consumer<Decision> diagnostics) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(nextZone, "zone");
        Objects.requireNonNull(currentThreats, "currentThreats");
        // Другой прямоугольник — другая зона: прежние прогнозы к ней не относятся.
        if (!nextZone.local().equals(zone)) {
            clear();
            zone = nextZone.local();
        }
        Set<UUID> present = new HashSet<>();
        Map<UUID, Integer> nextDangerous = new HashMap<>();
        for (UUID id : currentThreats) nextDangerous.put(id, 0);
        int budget = PREDICTIONS_PER_TICK;
        incomplete = 0;
        for (Observation sample : observations) {
            if (!present.add(sample.id())) continue;
            var position = sample.shell().position();
            boolean inside = currentThreats.contains(sample.id()) || nextZone.inflate(sample.reach()).contains(position);
            if (inside) nextDangerous.put(sample.id(), 0);
            Entry entry = entries.get(sample.id());
            Action action = entry == null ? Action.NEW : Action.CACHED;
            if (entry == null || !entry.matches(sample, nextZone)) {
                if (entry != null) action = Action.REBUILT;
                entries.remove(sample.id());
                if (budget-- <= 0) {
                    incomplete++;
                    report(diagnostics, sample, Action.BUDGET_DEFERRED, null, 0, inside);
                    continue;
                }
                try {
                    entry = new Entry(sample, nextZone, ShellRoutePrediction.predict(sample.shell(), nextZone,
                            sample.reach(), PREDICTION_LIMIT));
                } catch (IllegalArgumentException numericOverflow) {
                    // Чужой data pack/мод может дать конечные входы с невычислимым будущим.
                    incomplete++;
                    report(diagnostics, sample, Action.NUMERIC_FAILURE, null, 0, inside);
                    continue;
                }
                entries.put(sample.id(), entry);
                predictionCount++;
            }
            int elapsed = sample.age() - entry.initial.age();
            if (entry.prediction.dangerousAfter(elapsed)) {
                nextDangerous.merge(sample.id(), Math.max(0, entry.prediction.firstDangerTick() - elapsed), Math::min);
            }
            if (!entry.prediction.complete()) incomplete++;
            report(diagnostics, sample, action, entry.prediction, elapsed, inside);
        }
        entries.keySet().retainAll(present);
        dangerous = Map.copyOf(nextDangerous);
    }

    private static void report(Consumer<Decision> diagnostics, Observation sample, Action action,
                               ShellRoutePrediction prediction, int elapsed, boolean inside) {
        if (diagnostics != null) diagnostics.accept(new Decision(sample, action, prediction, elapsed, inside));
    }

    public enum Action { NEW, CACHED, REBUILT, BUDGET_DEFERRED, NUMERIC_FAILURE }

    /** prediction отсутствует при отложенном/невычислимом прогнозе. */
    public record Decision(Observation sample, Action action, ShellRoutePrediction prediction, int elapsed, boolean inside) {
        public String result() {
            if (inside) return "CURRENT_THREAT";
            if (prediction == null) return "UNKNOWN";
            if (prediction.dangerousAfter(elapsed)) return "FUTURE_THREAT";
            if (!prediction.complete()) return "HORIZON_LIMIT";
            return prediction.firstDangerTick() >= 0 ? "INTERSECTION_PASSED" : "NO_INTERSECTION";
        }
    }

    public record Observation(UUID id, int age, HostileShell shell, double reach) {
        public Observation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(shell, "shell");
            if (age < 0 || !(reach >= 0) || !Double.isFinite(reach)) throw new IllegalArgumentException("Invalid observation");
        }
    }

    private record Entry(Observation initial, ZoneVolume zone, ShellRoutePrediction prediction) {
        boolean matches(Observation sample, ZoneVolume current) {
            int elapsed = sample.age() - initial.age();
            try {
                return elapsed >= 0
                        && current.continues(zone, elapsed, sample.reach(), ZONE_DISTANCE_EPSILON, ZONE_SPEED_EPSILON)
                        && (prediction.complete() || elapsed < PREDICTION_LIMIT / 2)
                        && initial.reach() == sample.reach()
                        && initial.shell().motion().drag() == sample.shell().motion().drag()
                        && initial.shell().motion().gravity() == sample.shell().motion().gravity()
                        && initial.shell().positionAfter(elapsed).subtract(sample.shell().position()).length() <= POSITION_EPSILON
                        && initial.shell().velocityAfter(elapsed).subtract(sample.shell().velocity()).length() <= VELOCITY_EPSILON;
            } catch (IllegalArgumentException numericOverflow) {
                return false;
            }
        }
    }
}
