package com.electroradar.cannon.intercept;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.FlightHorizon;
import com.electroradar.cannon.ballistics.InterceptRequest;
import com.electroradar.cannon.ballistics.InterceptResult;
import com.electroradar.cannon.ballistics.InterceptSearch;
import com.electroradar.cannon.ballistics.Sweep;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * План боя одной автопушки против одного снаряда: окно огня и очередь сопровождения.
 *
 * <p><b>Перебор момента пуска.</b> Для каждого {@code L = 0, 1, …} (выстрел в фазе BlockEntity тика
 * {@code t₀ + L}) цель — {@link HostileShell#asTarget} с задержкой {@code L + d}, а встречу ищет
 * обычный {@link InterceptSearch}: отрезок перехватчика тика {@code n} проходит через положение
 * снаряда в том же серверном тике. Горизонт обрезан сроком: {@code L + d + n ≤ m_I − margin},
 * где {@code m_I} — последний индекс полёта снаряда до падения. Пустой результат на таком
 * горизонте доказывает, что встречи при этом {@code L} нет.</p>
 *
 * <p><b>Окно.</b> Окно открывается при первом {@code L}, для которого есть встреча и установка
 * успевает развернуться ({@link SlewModel#ticksToTurn} ≤ {@code L}), и закрывается, когда встреч
 * больше нет: снаряд упадёт раньше или уйдёт за досягаемость. <b>Сопровождение.</b> Дальше
 * выстрелы ставятся не чаще темпа автопушки; выстрел принимается, если требуемое направление
 * сместилось от предыдущего не больше, чем привод успевает пройти за промежуток. Подсказка
 * предыдущего тика встречи удерживает очередь на одной ветви решения.</p>
 */
public final class EngagementPlanner {
    /** Внешний предел горизонта перехватчика: время жизни автопушки ограничивает его раньше. */
    private static final int HORIZON_LIMIT_TICKS = 1_200;

    private EngagementPlanner() {
    }

    /**
     * Перехватчик: автопушка с зенитным снарядом.
     *
     * @param flight профиль снаряда (скорость по числу стволов, время жизни материала)
     * @param constraints углы крепления; семейство не задаётся — подходит любая ветвь
     * @param pivot центр вращения, мировые координаты
     * @param clearance запас высоты для {@link FlightHorizon#compute}
     * @param launchDelay тики от решения до первого отрезка (1 при прямом fireShot)
     * @param fireInterval тики между выстрелами (темп 15 автопушки CBC — 4)
     * @param kill модель поражения снаряда
     * @param aimable доступно ли установке мировое направление ствола: у установки на повёрнутой
     *                структуре пределы угла места заданы в её осях, а не в мировых
     */
    public record Interceptor(FlightCoefficients flight, AimConstraints constraints, Vector3 pivot, double clearance,
                              int launchDelay, int fireInterval, FragmentKillModel kill, Predicate<Vector3> aimable) {
        public Interceptor {
            Objects.requireNonNull(flight, "flight");
            Objects.requireNonNull(constraints, "constraints");
            Objects.requireNonNull(pivot, "pivot");
            Objects.requireNonNull(kill, "kill");
            Objects.requireNonNull(aimable, "aimable");
            if (!(clearance >= 0.0D) || launchDelay < 0 || fireInterval < 1) {
                throw new IllegalArgumentException("Invalid interceptor");
            }
        }

        /** Установка в мире: пределы {@code constraints} уже мировые. */
        public Interceptor(FlightCoefficients flight, AimConstraints constraints, Vector3 pivot, double clearance,
                           int launchDelay, int fireInterval, FragmentKillModel kill) {
            this(flight, constraints, pivot, clearance, launchDelay, fireInterval, kill, direction -> true);
        }
    }

    /**
     * Правила боя.
     *
     * @param planningTicks насколько вперёд искать момент пуска
     * @param maxShots сколько выстрелов планировать в очереди
     * @param deadlineMargin тики запаса до падения: встреча не позже {@code m_I − margin}
     * @param minInterceptTick наименьший тик встречи (взрыватель не взводится раньше)
     * @param minInterceptRange наименьшая дальность встречи от центра пушки: осколки не над своими
     * @param fuzeHalfWindow полуширина окна взведения вокруг тика встречи
     * @param proximityRadius радиус срабатывания взрывателя
     * @param leadDistance отступ подрыва до точки сближения
     */
    public record Policy(int planningTicks, int maxShots, int deadlineMargin, int minInterceptTick,
                         double minInterceptRange, int fuzeHalfWindow, double proximityRadius, double leadDistance) {
        public Policy {
            if (planningTicks < 0 || maxShots < 1 || deadlineMargin < 0 || minInterceptTick < 0
                    || !(minInterceptRange >= 0.0D) || fuzeHalfWindow < 0
                    || !(proximityRadius > 0.0D) || !(leadDistance >= 0.0D)) {
                throw new IllegalArgumentException("Invalid engagement policy");
            }
        }
    }

    /** Текущие углы установки, градусы. */
    public record Turret(double yaw, double pitch) {
    }

    /**
     * Цель боя.
     *
     * @param lastAirborneIndex последний индекс положения в полёте, {@link HostileShell.Impact#lastAirborneIndex()}
     * @param halfSize половина размера хитбокса
     * @param durabilityMass {@code durability_mass} снаряда
     */
    public record Target(HostileShell shell, int lastAirborneIndex, double halfSize, double durabilityMass) {
        public Target {
            Objects.requireNonNull(shell, "shell");
            if (lastAirborneIndex < 0 || !(halfSize >= 0.0D) || !(durabilityMass >= 0.0D)) {
                throw new IllegalArgumentException("Invalid engagement target");
            }
        }
    }

    /**
     * @param launchTick через сколько тиков после наблюдения стрелять
     * @param solution направление и тик встречи
     * @param interceptPoint точка встречи, мировые координаты
     * @param fuze программа взрывателя
     * @param killProbability модельная вероятность уничтожения без учёта разброса
     */
    public record PlannedShot(int launchTick, FireSolution solution, Vector3 interceptPoint,
                              InterceptFuze.Program fuze, double killProbability) {
    }

    public enum Status {
        /** Окно открыто, очередь запланирована. */
        FEASIBLE,
        /** Встречи есть, но установка не успевает развернуться до их окончания. */
        TOO_LATE,
        /** Встречи нет ни при каком моменте пуска до падения снаряда. */
        NO_SOLUTION
    }

    /**
     * @param openTick первый момент пуска; {@code −1}, если окна нет
     * @param closeTick последний момент пуска со встречей; {@code −1}, если окна нет
     * @param expectedKill вероятность уничтожения всей очередью
     */
    public record Engagement(Status status, int openTick, int closeTick, List<PlannedShot> shots, double expectedKill) {
        public Engagement {
            Objects.requireNonNull(status, "status");
            shots = List.copyOf(shots);
        }

        /** Серверный тик (от наблюдения) первой запланированной встречи. */
        public OptionalInt firstInterceptTick(int launchDelay) {
            return shots.isEmpty() ? OptionalInt.empty()
                    : OptionalInt.of(shots.get(0).launchTick() + launchDelay + shots.get(0).solution().tick());
        }
    }

    public static Engagement plan(Interceptor interceptor, Turret turret, SlewModel slew, Policy policy, Target target) {
        Objects.requireNonNull(interceptor, "interceptor");
        Objects.requireNonNull(turret, "turret");
        Objects.requireNonNull(slew, "slew");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(target, "target");
        FlightCoefficients flight = interceptor.flight();
        int delay = interceptor.launchDelay();
        FlightHorizon base = baseHorizon(interceptor);

        List<PlannedShot> shots = new ArrayList<>();
        boolean anySolution = false;
        int open = -1;
        int close = -1;
        FireSolution previous = null;
        int previousLaunch = 0;
        OptionalInt hint = OptionalInt.empty();
        for (int launch = 0; launch <= policy.planningTicks(); launch++) {
            int deadline = target.lastAirborneIndex() - policy.deadlineMargin() - launch - delay;
            // Позже встреча только ближе к падению: дальше пускать бессмысленно.
            if (deadline < policy.minInterceptTick()) break;
            int shift = launch + delay;
            InterceptRequest request = new InterceptRequest(flight,
                    target.shell().asTarget(interceptor.pivot(), shift), interceptor.constraints(),
                    base.until(deadline), hint);
            InterceptResult result = InterceptSearch.search(request, 0, Integer.MAX_VALUE,
                    solution -> acceptable(solution, target.shell(), interceptor, shift, policy));
            if (result.solution().isEmpty()) {
                if (open >= 0) break;
                hint = OptionalInt.empty();
                continue;
            }
            anySolution = true;
            FireSolution solution = result.solution().get();
            hint = OptionalInt.of(solution.tick());
            if (open < 0) {
                if (slew.ticksToTurn(turret.yaw(), turret.pitch(), solution.yaw(), solution.pitch()) > launch) continue;
                open = launch;
            } else if (launch - previousLaunch < interceptor.fireInterval()
                    || !slew.canTrack(angleDegrees(previous.direction(), solution.direction()), launch - previousLaunch)) {
                close = launch;
                continue;
            }
            shots.add(shot(interceptor, policy, target, launch, solution));
            previous = solution;
            previousLaunch = launch;
            close = launch;
            if (shots.size() >= policy.maxShots()) break;
        }
        if (shots.isEmpty()) {
            return new Engagement(anySolution ? Status.TOO_LATE : Status.NO_SOLUTION, -1, -1, List.of(), 0.0D);
        }
        double[] perShot = shots.stream().mapToDouble(PlannedShot::killProbability).toArray();
        return new Engagement(Status.FEASIBLE, open, close, shots, FragmentKillModel.salvo(perShot));
    }

    /**
     * Встреча при выстреле в этом тике ({@code L = 0}) без проверки разворота: так сопровождение
     * каждый тик пересчитывает решение по свежему состоянию снаряда, а готовность к огню проверяется
     * по фактическому направлению ствола.
     *
     * @param hint тик прежней встречи с этой целью: удерживает очередь на одной ветви решения
     */
    public static Optional<PlannedShot> immediate(Interceptor interceptor, Policy policy, Target target,
                                                  OptionalInt hint) {
        Objects.requireNonNull(interceptor, "interceptor");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hint, "hint");
        int delay = interceptor.launchDelay();
        int deadline = target.lastAirborneIndex() - policy.deadlineMargin() - delay;
        if (deadline < policy.minInterceptTick()) return Optional.empty();
        InterceptRequest request = new InterceptRequest(interceptor.flight(),
                target.shell().asTarget(interceptor.pivot(), delay), interceptor.constraints(),
                baseHorizon(interceptor).until(deadline), hint);
        return InterceptSearch.search(request, 0, Integer.MAX_VALUE,
                        solution -> acceptable(solution, target.shell(), interceptor, delay, policy))
                .solution().map(solution -> shot(interceptor, policy, target, 0, solution));
    }

    private static FlightHorizon baseHorizon(Interceptor interceptor) {
        return FlightHorizon.compute(interceptor.flight(), interceptor.constraints().maxPitch(),
                interceptor.clearance(), HORIZON_LIMIT_TICKS);
    }

    /** Встреча не раньше взведения и не ближе безопасной дальности. */
    private static boolean acceptable(FireSolution solution, HostileShell shell, Interceptor interceptor, int shift,
                                      Policy policy) {
        if (!interceptor.aimable().test(solution.direction())) return false;
        Vector3 pivot = interceptor.pivot();
        if (solution.tick() < policy.minInterceptTick()) return false;
        return shell.positionAfter(shift + solution.tick()).subtract(pivot).length() >= policy.minInterceptRange();
    }

    private static PlannedShot shot(Interceptor interceptor, Policy policy, Target target, int launch,
                                    FireSolution solution) {
        int tick = solution.tick();
        Sweep sweep = interceptor.flight().sweep(tick);
        Vector3 u = solution.direction();
        // Отрезок тика встречи — это скорость v(n+1) = U·u − w·ŷ; цель на нём на доле f.
        Vector3 velocity = new Vector3(u.x() * sweep.step(), u.y() * sweep.step() - sweep.fall(), u.z() * sweep.step());
        double before = solution.fraction() * velocity.length();
        double lead = Math.min(policy.leadDistance(), before);
        Vector3 relative = velocity.length() < 1.0E-9D ? Vector3.ZERO
                : velocity.scale(lead / velocity.length());
        double kill = interceptor.kill().killProbability(velocity, relative, target.halfSize(), target.durabilityMass());
        Vector3 point = target.shell().positionAfter(launch + interceptor.launchDelay() + tick);
        return new PlannedShot(launch, solution, point,
                InterceptFuze.Program.around(tick, policy.fuzeHalfWindow(), policy.proximityRadius(), policy.leadDistance()),
                kill);
    }

    static double angleDegrees(Vector3 a, Vector3 b) {
        double cos = Math.max(-1.0D, Math.min(1.0D, a.dot(b) / Math.max(1.0E-12D, a.length() * b.length())));
        return Math.toDegrees(Math.acos(cos));
    }
}
