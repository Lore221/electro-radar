package com.electroradar.radar.alarm;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.electroradar.cannon.intercept.HostileShell;

/** Кэш принадлежит одному Alarm. Ни Entity, ни сетевые права, ни UUID после потери доступа не хранит. */
public final class ShellThreatTracker {
    public static final int PREDICTION_LIMIT = 4_096;
    public static final int PREDICTIONS_PER_TICK = 8;
    private static final double POSITION_EPSILON = 1.0E-4D;
    private static final double VELOCITY_EPSILON = 1.0E-5D;

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
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(nextZone, "zone");
        Objects.requireNonNull(currentThreats, "currentThreats");
        if (!nextZone.equals(zone)) {
            clear();
            zone = nextZone;
        }
        Set<UUID> present = new HashSet<>();
        Map<UUID, Integer> nextDangerous = new HashMap<>();
        for (UUID id : currentThreats) nextDangerous.put(id, 0);
        int budget = PREDICTIONS_PER_TICK;
        incomplete = 0;
        for (Observation sample : observations) {
            if (!present.add(sample.id())) continue;
            var position = sample.shell().position();
            if (nextZone.inflate(sample.reach()).intersects(position, position)) nextDangerous.put(sample.id(), 0);
            Entry entry = entries.get(sample.id());
            if (entry == null || !entry.matches(sample)) {
                entries.remove(sample.id());
                if (budget-- <= 0) {
                    incomplete++;
                    continue;
                }
                try {
                    entry = new Entry(sample, ShellRoutePrediction.predict(sample.shell(), nextZone,
                            sample.reach(), PREDICTION_LIMIT));
                } catch (IllegalArgumentException numericOverflow) {
                    // Чужой data pack/мод может дать конечные входы с невычислимым будущим.
                    incomplete++;
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
        }
        entries.keySet().retainAll(present);
        dangerous = Map.copyOf(nextDangerous);
    }

    public record Observation(UUID id, int age, HostileShell shell, double reach) {
        public Observation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(shell, "shell");
            if (age < 0 || !(reach >= 0) || !Double.isFinite(reach)) throw new IllegalArgumentException("Invalid observation");
        }
    }

    private record Entry(Observation initial, ShellRoutePrediction prediction) {
        boolean matches(Observation sample) {
            int elapsed = sample.age() - initial.age();
            try {
                return elapsed >= 0
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
