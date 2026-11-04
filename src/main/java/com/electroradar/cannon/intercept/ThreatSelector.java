package com.electroradar.cannon.intercept;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * Выбор снаряда для перехвата.
 *
 * <p><b>Угроза.</b> Снаряд угрожает, если прогноз падения ({@link HostileShell#predictImpact})
 * заканчивается ударом о блок внутри обороняемой зоны. Свои уходящие снаряды падают далеко и
 * отсекаются тем же правилом, без отдельного «свой-чужой». Снаряд, уходящий в незагруженную
 * область или летящий дольше проверенного, угрозой не считается: падение не доказано.</p>
 *
 * <p><b>Приоритет.</b> Из угроз с выполнимым боем ({@link EngagementPlanner.Status#FEASIBLE})
 * первым идёт снаряд с наименьшим запасом времени {@code slack = m_I − t_встречи}: это перехват,
 * который нельзя отложить. Дальше — ближе к центру зоны, затем выше ожидаемая вероятность
 * уничтожения. Текущая цель удерживается, пока другая не срочнее её больше чем на
 * {@code switchMargin} тиков: так очередь не бросается между двумя почти равными снарядами.</p>
 */
public final class ThreatSelector {
    private ThreatSelector() {
    }

    /** Обороняемая зона: шар вокруг центра. */
    public record DefendedZone(Vector3 center, double radius) {
        public DefendedZone {
            Objects.requireNonNull(center, "center");
            if (!(radius > 0.0D) || !Double.isFinite(radius)) throw new IllegalArgumentException("Invalid zone");
        }

        public double distance(Vector3 point) {
            return point.subtract(center).length();
        }

        public boolean contains(Vector3 point) {
            return distance(point) <= radius;
        }
    }

    /**
     * Снаряд, уже рассчитанный для этой установки.
     *
     * @param id идентичность цели (например, {@code TargetRef}); сравнивается через {@code equals}
     */
    public record Candidate(Object id, HostileShell.Impact impact, EngagementPlanner.Engagement engagement) {
        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(impact, "impact");
            Objects.requireNonNull(engagement, "engagement");
        }
    }

    /**
     * @param slack тики между первой запланированной встречей и падением
     * @param impactDistance расстояние точки падения от центра зоны
     */
    public record Threat(Candidate candidate, int slack, double impactDistance) {
        public Object id() {
            return candidate.id();
        }
    }

    private static final Comparator<Threat> URGENCY = Comparator.comparingInt(Threat::slack)
            .thenComparingDouble(Threat::impactDistance)
            .thenComparingDouble(threat -> -threat.candidate().engagement().expectedKill());

    /** Угрозы с выполнимым боем в порядке срочности. */
    public static List<Threat> rank(List<Candidate> candidates, DefendedZone zone, int launchDelay) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(zone, "zone");
        List<Threat> threats = new ArrayList<>();
        for (Candidate candidate : candidates) {
            HostileShell.Impact impact = candidate.impact();
            if (impact.status() != TrajectoryTrace.Status.BLOCKED || !zone.contains(impact.point())) continue;
            OptionalInt intercept = candidate.engagement().firstInterceptTick(launchDelay);
            if (candidate.engagement().status() != EngagementPlanner.Status.FEASIBLE || intercept.isEmpty()) continue;
            threats.add(new Threat(candidate, impact.tick() - intercept.getAsInt(), zone.distance(impact.point())));
        }
        threats.sort(URGENCY);
        return threats;
    }

    /**
     * Цель на этот тик.
     *
     * @param ranked результат {@link #rank}
     * @param current текущая цель или {@code null}
     * @param switchMargin на сколько тиков другая угроза должна быть срочнее, чтобы сменить цель
     */
    public static Optional<Threat> select(List<Threat> ranked, Object current, int switchMargin) {
        Objects.requireNonNull(ranked, "ranked");
        if (switchMargin < 0) throw new IllegalArgumentException("switchMargin must be non-negative");
        if (ranked.isEmpty()) return Optional.empty();
        Threat best = ranked.get(0);
        if (current == null) return Optional.of(best);
        for (Threat threat : ranked) {
            if (threat.id().equals(current)) {
                return Optional.of(best.slack() + switchMargin < threat.slack() ? best : threat);
            }
        }
        return Optional.of(best);
    }
}
