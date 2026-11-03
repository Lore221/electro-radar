package com.electroradar.cannon.ballistics;

import java.util.Objects;

/**
 * Замкнутые коэффициенты полёта одного профиля: {@code S(n) = (1 − q^n)/k}, {@code A = (1 − k/2)·S},
 * {@code B = (n − A)/k}, {@code q = 1 − k}; при {@code k = 0} — их пределы {@code S = A = n},
 * {@code B = n²/2}.
 *
 * <p>Объект неизменяемый и потокобезопасный; его стоит создать один раз на профиль. При малом
 * {@code k·n} разность {@code n − A} теряет значащие цифры, поэтому {@code B} считается
 * биномиальным рядом, а {@code S} — через {@code expm1}.</p>
 */
public final class FlightCoefficients {
    /** Порог {@code k·max(1, n)}, ниже которого {@code B} считается рядом. */
    private static final double SERIES_THRESHOLD = 1.0E-3D;
    /** При {@code k·n < 1e-3} шестой член ряда меньше {@code 1e-18} от первого. */
    private static final int SERIES_TERMS = 6;

    private final ProjectileProfile profile;
    /** {@code ln q}; ноль при {@code k = 0}. */
    private final double lambda;
    /** {@code 1 − k/2}: средняя скорость за тик при шаге {@code v + a/2}. */
    private final double midpoint;

    private FlightCoefficients(ProjectileProfile profile) {
        this.profile = profile;
        this.lambda = Math.log1p(-profile.drag());
        this.midpoint = 1.0D - profile.drag() / 2.0D;
    }

    public static FlightCoefficients of(ProjectileProfile profile) {
        return new FlightCoefficients(Objects.requireNonNull(profile, "profile"));
    }

    public ProjectileProfile profile() {
        return profile;
    }

    /** Коэффициенты отрезка столкновений в тик {@code tick}. */
    public Sweep sweep(int tick) {
        if (tick < 0) throw new IllegalArgumentException("Flight tick must be non-negative");
        double k = profile.drag();
        double n = tick;
        double decay;
        double sum;
        double drop;
        if (k == 0.0D) {
            decay = 1.0D;
            sum = n;
            drop = n * n / 2.0D;
        } else {
            double shrink = Math.expm1(lambda * n);
            decay = 1.0D + shrink;
            sum = -shrink / k;
            drop = k * Math.max(1.0D, n) < SERIES_THRESHOLD ? seriesDrop(tick, k) : (n - midpoint * sum) / k;
        }
        double s = profile.speed();
        double g = profile.gravity();
        // S(n + 1) = S(n) + q^n: вертикальная длина отрезка без второго вычитания.
        return new Sweep(tick, profile.spawnOffset() + s * midpoint * sum, s * (1.0D - k) * decay,
                g * drop, g * (sum + decay));
    }

    /** {@code B(n) = Σ (−k)^j·[C(n, j + 2) + C(n, j + 1)/2]} для малого {@code k·n}. */
    private static double seriesDrop(int tick, double k) {
        double[] choose = new double[SERIES_TERMS + 2];
        choose[0] = 1.0D;
        for (int j = 1; j < choose.length; j++) {
            choose[j] = choose[j - 1] * (tick - j + 1) / j;
        }
        double result = 0.0D;
        double power = 1.0D;
        for (int j = 0; j < SERIES_TERMS; j++) {
            result += power * (choose[j + 2] + choose[j + 1] / 2.0D);
            power *= -k;
        }
        return result;
    }

    /**
     * Верхняя граница высоты любой точки отрезка тика {@code tick} над центром пушки при угле места
     * не выше {@code pitchCeiling}: {@code (R + U)·σ − g·B}, {@code σ = max(0, sin θ)}. Функция
     * вогнута по {@code n}, поэтому после вершины только убывает.
     */
    double ceiling(int tick, double sinPitchCeiling) {
        Sweep sweep = sweep(tick);
        return sweep.outerRadius() * Math.max(0.0D, sinPitchCeiling) - sweep.drop();
    }

    /** {@code max |dR/dn| = s·A′(0)}; {@code A′} убывает. */
    double radiusRateBound() {
        double k = profile.drag();
        return k == 0.0D ? profile.speed() : profile.speed() * midpoint * (-lambda) / k;
    }

    /** {@code max |dU/dn| = s·|ln q|·q}; {@code |U′|} убывает. */
    double stepRateBound() {
        return profile.speed() * (-lambda) * (1.0D - profile.drag());
    }

    /** {@code max |dw/dn| = g·|ln q|·q/k} (при {@code k = 0} — {@code g}); {@code w′} убывает. */
    double fallRateBound() {
        double k = profile.drag();
        return k == 0.0D ? profile.gravity() : profile.gravity() * (-lambda) * (1.0D - k) / k;
    }

    /**
     * Граница {@code |d(g·B)/dn|} на {@code [0, tick]}: {@code B′ ≤ min(n + 1/2, 1/k)} (неравенства
     * Бернулли и {@code −ln q ≥ k}) и {@code B′ ≥ −k/12} около нуля; без вычитания близких чисел.
     */
    double dropRateBound(int tick) {
        double k = profile.drag();
        double rate = k == 0.0D ? tick : Math.min(tick + 0.5D, 1.0D / k) + k;
        return profile.gravity() * rate;
    }
}
