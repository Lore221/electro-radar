package com.electroradar.cannon.ballistics;

import java.util.Objects;
import java.util.Optional;

/**
 * Итог одного вызова {@link InterceptSearch#search}.
 *
 * @param status что можно утверждать по итогу
 * @param solution принятое решение; есть только при {@link Status#FOUND}
 * @param nextTick с какого тика продолжить ту же задачу при {@link Status#PENDING}
 * @param visitedTicks сколько тиков оценено (включая пропущенные с доказательством)
 * @param kernelChecks сколько тиков проверено точным ядром {@link SweepIntersection}
 */
public record InterceptResult(Status status, Optional<FireSolution> solution, int nextTick,
                              int visitedTicks, int kernelChecks) {
    public InterceptResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(solution, "solution");
        if ((status == Status.FOUND) != solution.isPresent() || nextTick < 0 || visitedTicks < 0
                || kernelChecks < 0 || kernelChecks > visitedTicks) {
            throw new IllegalArgumentException("Invalid intercept result");
        }
    }

    public enum Status {
        /** Найдено решение, принятое потребителем. */
        FOUND,
        /** Бюджет исчерпан; решения ещё могут существовать после {@code nextTick}. */
        PENDING,
        /** Весь доказанный горизонт пройден: принятого попадания в модели нет. */
        NO_SOLUTION,
        /** Пройден горизонт {@link FlightHorizon.Bound#LIMIT}: за ним попадания не исключены. */
        NO_SOLUTION_WITHIN_LIMIT
    }
}
