package com.electroradar.cannon.ballistics;

import java.util.Objects;

/**
 * Последний тик полёта, в котором попадание ещё возможно.
 *
 * <p><b>Почему граница доказуема.</b> Любая точка отрезка тика {@code n} при угле места не выше
 * {@code θ} лежит не выше {@code top(n) = (R + U)·max(0, sin θ) − g·B} над центром пушки, а
 * {@code top} вогнута по {@code n}. Как только {@code top(n)} опускается ниже плоскости удаления
 * сущностей (ванильная проверка {@code minBuildHeight − 64}, которую CBC не переопределяет) с
 * запасом на хитбокс снаряда, она уже не поднимается: ни снаряд, ни живая цель там не встречаются.
 * Без гравитации снаряд не падает, но CBC прекращает проверку столкновений, когда шаг короче
 * 0,01 блока; без гравитации и сопротивления конечной физической границы нет.</p>
 *
 * @param lastTick последний тик, который нужно проверить; {@code −1} — ни одного
 * @param bound что ограничило горизонт
 */
public record FlightHorizon(int lastTick, Bound bound) {
    /** Ванильная глубина плоскости удаления сущностей под {@code minBuildHeight}. */
    public static final int KILL_PLANE_DEPTH = 64;

    public FlightHorizon {
        Objects.requireNonNull(bound, "bound");
        if (lastTick < -1) throw new IllegalArgumentException("Invalid flight horizon");
    }

    public enum Bound {
        /** Снаряд при любом допустимом угле ниже плоскости удаления — доказано моделью. */
        KILL_PLANE,
        /** Без гравитации отрезок стал короче 0,01 и CBC больше не проверяет столкновения. */
        MIN_SWEEP_LENGTH,
        /** Время жизни снаряда: последний отрезок перед удалением. */
        LIFETIME,
        /** Внешний предел перебора; после него попадания не исключены. */
        LIMIT
    }

    /** Пустой результат поиска на таком горизонте доказывает отсутствие попадания в модели. */
    public boolean proven() {
        return bound != Bound.LIMIT;
    }

    /**
     * Насколько центр пушки выше самой низкой точки, где попадание ещё возможно.
     *
     * @param cannonCenterY высота центра пушки
     * @param minBuildHeight нижняя граница мира измерения
     * @param projectileReach половина наибольшего размера хитбокса снаряда: CBC расширяет на неё
     *                        хитбокс цели ({@code targetBB.inflate(reach)})
     */
    public static double clearance(double cannonCenterY, int minBuildHeight, double projectileReach) {
        if (!Double.isFinite(cannonCenterY) || !(projectileReach >= 0.0D)) {
            throw new IllegalArgumentException("Invalid clearance input");
        }
        return Math.max(0.0D, cannonCenterY - (minBuildHeight - KILL_PLANE_DEPTH) + projectileReach);
    }

    /**
     * Вычисляет горизонт профиля; результат зависит только от профиля, угла и высоты пушки и может
     * кешироваться на их сочетание.
     *
     * @param maxPitch наибольший угол места крепления в градусах
     * @param clearance результат {@link #clearance(double, int, double)}
     * @param tickLimit внешний предел; если граница модели дальше, горизонт будет {@link Bound#LIMIT}
     */
    public static FlightHorizon compute(FlightCoefficients flight, double maxPitch, double clearance, int tickLimit) {
        Objects.requireNonNull(flight, "flight");
        if (!(maxPitch >= -90.0D && maxPitch <= 90.0D) || !(clearance >= 0.0D) || !Double.isFinite(clearance)
                || tickLimit < 0 || tickLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid flight horizon input");
        }
        ProjectileProfile profile = flight.profile();
        int limit = tickLimit;
        Bound limitBound = Bound.LIMIT;
        if (profile.lifetimeTicks().isPresent() && lastLivingTick(profile.lifetimeTicks().getAsInt()) <= limit) {
            limit = lastLivingTick(profile.lifetimeTicks().getAsInt());
            limitBound = Bound.LIFETIME;
        }
        if (profile.gravity() > 0.0D) {
            int below = firstBelowKillPlane(flight, Math.sin(Math.toRadians(maxPitch)), clearance, limit + 1);
            return below <= limit ? new FlightHorizon(below - 1, Bound.KILL_PLANE) : new FlightHorizon(limit, limitBound);
        }
        if (profile.drag() > 0.0D) {
            int last = lastLongSweep(flight, limit);
            return last < limit ? new FlightHorizon(last, Bound.MIN_SWEEP_LENGTH) : new FlightHorizon(limit, limitBound);
        }
        return new FlightHorizon(limit, limitBound);
    }

    /**
     * Последний отрезок снаряда со временем жизни {@code T}. CBC сначала проверяет отрезок, затем
     * уменьшает {@code ageRemaining} и удаляет снаряд при нуле ({@code AbstractAutocannonProjectile.tick}):
     * отрезков ровно {@code T}, с номерами {@code 0…T−1}; при {@code T = 0} снаряд успевает один отрезок.
     */
    static int lastLivingTick(int lifetimeTicks) {
        return Math.max(0, lifetimeTicks - 1);
    }

    /**
     * Первый тик в {@code [0, end]} ниже плоскости или {@code end + 1}. {@code top(0) ≥ 0}, а до
     * вершины {@code top} растёт, поэтому условие ложно до вершины и истинно после пересечения:
     * двоичный поиск корректен без отдельного поиска вершины.
     */
    private static int firstBelowKillPlane(FlightCoefficients flight, double sinPitch, double clearance, int end) {
        if (flight.ceiling(end, sinPitch) >= -clearance) return end + 1;
        int lo = 0;
        int hi = end;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (flight.ceiling(mid, sinPitch) < -clearance) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** Последний тик в {@code [−1, limit]}, где отрезок без гравитации ещё не короче 0,01. */
    private static int lastLongSweep(FlightCoefficients flight, int limit) {
        if (flight.sweep(limit).step() >= SweepIntersection.MIN_SWEEP_LENGTH) return limit;
        int lo = -1;
        int hi = limit;
        // Шаг s·q^(n+1) строго убывает: lo — последний длинный, hi — первый короткий.
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            if (flight.sweep(mid).step() >= SweepIntersection.MIN_SWEEP_LENGTH) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
