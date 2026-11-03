package com.electroradar.cannon.ballistics;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Полный перебор тиков {@code 0…N} с пропуском только тех, где попадание исключено оценкой.
 *
 * <p><b>Почему пропуск безопасен.</b> Для концов отрезка введём расстояния до сфер
 * {@code e_j = |D_j| − r_j}, {@code j = 0, 1}, где {@code D_j = (x, y + drop + j·fall, z)},
 * {@code r_0 = radius}, {@code r_1 = radius + step}. Попадание в тик требует двух условий:</p>
 * <ul>
 *   <li>{@code min(F_0, F_1) ≤ a⁺/4} — из {@code F(f) = (1−f)·F_0 + f·F_1 − a·f·(1−f)}; в расстояниях
 *       это {@code min_j(e_j − δ_j) ≤ 0} с {@code δ_j = √(r_j² + τ) − r_j}, {@code τ = a⁺(N)/4}
 *       ({@code a} растёт по {@code n});</li>
 *   <li>{@code max(e_0, e_1) ≥ 0}: при {@code a ≤ 0} сферы вложены, при {@code a > 0} {@code F}
 *       выпукла по {@code f}, и максимум достигается на концах.</li>
 * </ul>
 * <p>Если запас {@code margin = max(min_j(e_j − δ_j), −max_j e_j)} положителен, то, поскольку
 * {@code e_j} меняется не быстрее {@code lip} за тик, а {@code r_j} не убывает (значит, {@code δ_j}
 * не растёт), попаданий нет и в следующих {@code ⌈margin/lip⌉} тиках. Остальные тики проверяет
 * точное ядро {@link SweepIntersection}.</p>
 *
 * <p>Перебор идёт по возрастанию тика, поэтому без подсказки первое принятое решение — самое
 * раннее. С подсказкой поиск сначала проверяет её окрестность и возвращает найденное там решение
 * без проверки более ранних тиков: так уже наведённое орудие остаётся на прежнем решении, а не
 * перескакивает на другое. Самое раннее решение при подсказке не гарантируется — для него
 * подсказку не передают. Решение о пригодности (например, успеет ли привод) принимает
 * потребитель: отклонённое решение не останавливает поиск, иначе уже наведённая пушка может
 * потерять доступный выстрел.</p>
 */
public final class InterceptSearch {
    /** Относительный запас на округление: тик пропускается, только если запас заметно больше нуля. */
    private static final double MARGIN_EPSILON = 1.0E-9D;
    /** Соседи тика-подсказки, проверяемые до перебора. */
    private static final int HINT_RADIUS = 1;

    private InterceptSearch() {
    }

    /** Первое допустимое решение без дополнительных условий потребителя и без ограничения работы. */
    public static InterceptResult search(InterceptRequest request) {
        return search(request, 0, Integer.MAX_VALUE, solution -> true);
    }

    /**
     * Ищет первое решение, принятое {@code acceptor}.
     *
     * @param fromTick с какого тика начать: 0 для новой задачи или {@link InterceptResult#nextTick()}
     *                 для продолжения той же задачи; подсказка проверяется только при 0
     * @param maxVisits сколько тиков можно оценить за вызов; при положительном бюджете каждый
     *                  вызов продвигает перебор, при нулевом возвращается PENDING на {@code fromTick}
     * @param acceptor решает, подходит ли найденное решение потребителю; должен быть чистым
     */
    public static InterceptResult search(InterceptRequest request, int fromTick, int maxVisits,
                                         Predicate<FireSolution> acceptor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(acceptor, "acceptor");
        if (fromTick < 0 || maxVisits < 0) throw new IllegalArgumentException("Invalid search window");
        return new Scan(request, maxVisits, acceptor).run(fromTick);
    }

    private static final class Scan {
        private final InterceptRequest request;
        private final FlightCoefficients flight;
        private final int lastTick;
        private final int maxVisits;
        private final Predicate<FireSolution> acceptor;
        /** {@code τ = a⁺(N)/4}: наибольший выступ промежуточных сфер. */
        private final double bulgeSlack;
        /** Верхняя граница {@code |de_j/dn|} на всём горизонте. */
        private final double lipschitz;
        private int visits;
        private int checks;

        private Scan(InterceptRequest request, int maxVisits, Predicate<FireSolution> acceptor) {
            this.request = request;
            this.flight = request.flight();
            this.lastTick = request.horizon().lastTick();
            this.maxVisits = maxVisits;
            this.acceptor = acceptor;
            int end = Math.max(0, lastTick);
            this.bulgeSlack = Math.max(0.0D, flight.sweep(end).bulge()) / 4.0D;
            // |dD_j/dn| ≤ |X′| + |d(g·B)/dn| + |w′|, |dr_j/dn| ≤ |R′| + |U′|; конец отрезка (j = 1) мажорирует оба.
            this.lipschitz = request.target().speedBound(end + 1) + flight.dropRateBound(end + 1)
                    + flight.fallRateBound() + flight.radiusRateBound() + flight.stepRateBound(); 
        }

        private InterceptResult run(int fromTick) {
            if (fromTick == 0 && request.hintTick().isPresent()) {
                int hint = request.hintTick().getAsInt();
                int first = Math.max(0, hint - HINT_RADIUS);
                int last = Math.min(lastTick, hint + HINT_RADIUS);
                // Подсказка не делится между вызовами и обязана оставить основному перебору хотя бы
                // один тик: иначе продолжение снова начиналось бы с 0 и повторяло её бесконечно.
                if (last - first + 1 >= maxVisits) last = first - 1;
                for (int tick = first; tick <= last; tick++) {
                    visits++;
                    Optional<FireSolution> found = check(tick);
                    if (found.isPresent()) return result(InterceptResult.Status.FOUND, found, tick);
                }
            }
            int tick = fromTick;
            while (tick <= lastTick) {
                if (visits >= maxVisits) return result(InterceptResult.Status.PENDING, Optional.empty(), tick);
                visits++;
                Sweep sweep = flight.sweep(tick);
                Vector3 target = request.target().positionAt(tick);
                double margin = margin(sweep, target);
                double epsilon = MARGIN_EPSILON
                        * Math.max(1.0D, sweep.outerRadius() + target.length() + Math.abs(sweep.drop()));
                if (margin > epsilon) {
                    long skip = Math.max(1L, (long) Math.ceil((margin - epsilon) / lipschitz));
                    tick = (int) Math.min((long) lastTick + 1L, tick + skip);
                    continue;
                }
                Optional<FireSolution> found = check(sweep, target);
                if (found.isPresent()) return result(InterceptResult.Status.FOUND, found, tick);
                tick++;
            }
            InterceptResult.Status exhausted = request.horizon().proven()
                    ? InterceptResult.Status.NO_SOLUTION : InterceptResult.Status.NO_SOLUTION_WITHIN_LIMIT;
            return result(exhausted, Optional.empty(), Math.max(0, lastTick + 1));
        }

        private Optional<FireSolution> check(int tick) {
            return check(flight.sweep(tick), request.target().positionAt(tick));
        }

        private Optional<FireSolution> check(Sweep sweep, Vector3 target) {
            checks++;
            for (FireSolution solution : SweepIntersection.solve(sweep, target, request.constraints())) {
                if (acceptor.test(solution)) return Optional.of(solution);
            }
            return Optional.empty();
        }

        /** Положительный запас доказывает, что в этом тике попадания нет; см. описание класса. */
        private double margin(Sweep sweep, Vector3 target) {
            double horizontal = target.x() * target.x() + target.z() * target.z();
            double start = target.y() + sweep.drop();
            double end = start + sweep.fall();
            double outsideStart = Math.sqrt(horizontal + start * start) - sweep.radius();
            double outsideEnd = Math.sqrt(horizontal + end * end) - sweep.outerRadius();
            double beyondBulge = Math.min(outsideStart - slack(sweep.radius()), outsideEnd - slack(sweep.outerRadius()));
            return Math.max(beyondBulge, -Math.max(outsideStart, outsideEnd));
        }

        /** {@code √(r² + τ) − r} без вычитания близких чисел. */
        private double slack(double radius) {
            return bulgeSlack == 0.0D ? 0.0D : bulgeSlack / (Math.sqrt(radius * radius + bulgeSlack) + radius);
        }

        private InterceptResult result(InterceptResult.Status status, Optional<FireSolution> solution, int nextTick) {
            return new InterceptResult(status, solution, nextTick, visits, checks);
        }
    }
}
