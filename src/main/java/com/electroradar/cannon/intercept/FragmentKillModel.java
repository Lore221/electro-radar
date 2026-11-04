package com.electroradar.cannon.intercept;

import java.util.Objects;

import com.electroradar.cannon.ballistics.Vector3;

/**
 * Вероятность уничтожения снаряда подрывом перехватчика: фугасная зона и осколочный конус CBC.
 *
 * <p><b>Конус повторяет {@code CBCProjectileBurst.spawnConeBurst}.</b> Осколок получает скорость
 * {@code c·(f + t₁·s·r + t₂·s·u)}, где {@code f} — направление снаряда, {@code r = f × ŷ},
 * {@code u = f × r} (CBC их не нормирует, оба длины {@code |r| = cos(угла места)}), {@code s} —
 * {@code shrapnel_spread}, {@code t₁, t₂} — разности двух равномерных, то есть треугольные на
 * {@code [−1, 1]}, а {@code c ≥ 1,4·|v|}. Когда осколок продвинулся вперёд на {@code d}, его боковое
 * смещение равно {@code d·s·(t₁·r + t₂·u)} и от скорости не зависит. Цель с половиной размера
 * {@code a}, стоящая впереди на {@code d} со смещением {@code (y₀, z₀)} по осям {@code r̂, û},
 * задета осколком с вероятностью {@code p = P(|w·t₁ − y₀| ≤ a)·P(|w·t₂ − z₀| ≤ a)},
 * {@code w = d·s·|r|}. Число попаданий из {@code N} — биномиальное, а уничтожение требует
 * {@code ⌈масса цели / масса осколка⌉} попаданий (масса — {@code durability_mass} данных CBC).</p>
 *
 * <p>Упрощения: цель неподвижна, пока осколки до неё летят (осколки в 2–3 раза быстрее снаряда),
 * гравитация осколков (≤ 0,3 блока за 5 тиков) и начальный разброс ±0,0625 не учитываются.</p>
 *
 * @param fragments число осколков {@code shrapnel_count}
 * @param spread боковой разброс {@code shrapnel_spread}
 * @param fragmentSpeedFactor наименьшая доля скорости снаряда у осколка (CBC: 1,4)
 * @param fragmentLifetime время жизни осколка, тики
 * @param fragmentMass {@code durability_mass} осколка
 * @param blastRadius радиус фугасного поражения — параметр мода, а не данных CBC
 * @param blastMaxDurability наибольшая {@code durability_mass} цели, которую разрушает фугасное действие
 */
public record FragmentKillModel(int fragments, double spread, double fragmentSpeedFactor, int fragmentLifetime,
                                double fragmentMass, double blastRadius, double blastMaxDurability) {
    public FragmentKillModel {
        if (fragments < 0 || !(spread >= 0.0D) || !(fragmentSpeedFactor > 0.0D) || fragmentLifetime < 0
                || !(fragmentMass > 0.0D) || !(blastRadius >= 0.0D) || !(blastMaxDurability >= 0.0D)) {
            throw new IllegalArgumentException("Invalid fragment kill model");
        }
    }

    /** Зенитный снаряд автопушки CBC: данные {@code flak_autocannon} и {@code flak_burst}. */
    public static FragmentKillModel cbcFlak(double blastRadius, double blastMaxDurability) {
        return new FragmentKillModel(15, 0.25D, 1.4D, 5, 0.75D, blastRadius, blastMaxDurability);
    }

    /**
     * @param flakVelocity скорость перехватчика в момент подрыва
     * @param relative положение цели относительно точки подрыва
     * @param targetHalfSize половина размера хитбокса цели
     * @param durabilityMass {@code durability_mass} цели (HE-снаряд 2, бронебойная болванка 8)
     */
    public double killProbability(Vector3 flakVelocity, Vector3 relative, double targetHalfSize, double durabilityMass) {
        Objects.requireNonNull(flakVelocity, "flakVelocity");
        Objects.requireNonNull(relative, "relative");
        if (!(targetHalfSize >= 0.0D) || !(durabilityMass >= 0.0D)) throw new IllegalArgumentException("Invalid target");
        if (relative.length() <= blastRadius && durabilityMass <= blastMaxDurability) return 1.0D;
        double speed = flakVelocity.length();
        if (speed < 1.0E-9D || fragments == 0) return 0.0D;
        Vector3 forward = flakVelocity.scale(1.0D / speed);
        double ahead = relative.dot(forward);
        if (ahead <= 0.0D || ahead > fragmentSpeedFactor * speed * fragmentLifetime) return 0.0D;

        Vector3 right = cross(forward, new Vector3(0.0D, 1.0D, 0.0D));
        if (right.dot(right) < 1.0E-6D) right = new Vector3(1.0D, 0.0D, 0.0D);
        Vector3 up = cross(forward, right);
        double scale = right.length();
        double width = ahead * spread * scale;
        double lateralRight = relative.dot(right) / scale;
        double lateralUp = relative.dot(up) / up.length();
        double perFragment = axisHit(lateralRight, targetHalfSize, width) * axisHit(lateralUp, targetHalfSize, width);
        int required = Math.max(1, (int) Math.ceil(durabilityMass / fragmentMass - 1.0E-9D));
        return binomialTail(fragments, perFragment, required);
    }

    /** {@code P(|w·t − offset| ≤ a)} для треугольного {@code t} на {@code [−1, 1]}. */
    static double axisHit(double offset, double halfSize, double width) {
        if (width < 1.0E-9D) return Math.abs(offset) <= halfSize ? 1.0D : 0.0D;
        return triangularCdf((offset + halfSize) / width) - triangularCdf((offset - halfSize) / width);
    }

    static double triangularCdf(double x) {
        if (x <= -1.0D) return 0.0D;
        if (x >= 1.0D) return 1.0D;
        return x <= 0.0D ? (x + 1.0D) * (x + 1.0D) / 2.0D : 1.0D - (1.0D - x) * (1.0D - x) / 2.0D;
    }

    /** {@code P(X ≥ k)} для {@code X ~ Bin(n, p)}. */
    static double binomialTail(int n, double p, int k) {
        if (k <= 0) return 1.0D;
        if (k > n || p <= 0.0D) return 0.0D;
        if (p >= 1.0D) return 1.0D;
        double term = Math.pow(1.0D - p, n);
        double below = 0.0D;
        for (int i = 0; i < k; i++) {
            below += term;
            term *= (double) (n - i) / (i + 1) * p / (1.0D - p);
        }
        return Math.max(0.0D, Math.min(1.0D, 1.0D - below));
    }

    /** Вероятность уничтожения хотя бы одним из независимых выстрелов. */
    public static double salvo(double... perShot) {
        double survive = 1.0D;
        for (double p : perShot) survive *= 1.0D - Math.max(0.0D, Math.min(1.0D, p));
        return 1.0D - survive;
    }

    private static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }
}
