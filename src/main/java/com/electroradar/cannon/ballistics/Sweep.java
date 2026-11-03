package com.electroradar.cannon.ballistics;

/**
 * Отрезок проверки столкновений CBC в тик {@code n}: {@code p_n → p_n + v_(n+1)}.
 *
 * <p>Для единичного направления ствола {@code u} точка отрезка с долей {@code f ∈ [0, 1]} равна
 * {@code (radius + f·step)·u − (drop + f·fall)·ŷ} относительно центра пушки. Все коэффициенты не
 * зависят от направления, поэтому при фиксированном {@code n} возможные положения снаряда лежат
 * на семействе сфер с центром {@code −(drop + f·fall)·ŷ} и радиусом {@code radius + f·step}.</p>
 *
 * @param tick номер тика полёта {@code n}, начиная с 0
 * @param radius осевое расстояние начала отрезка {@code R(n) = L + s·A(n)}
 * @param step осевая длина отрезка {@code U(n) = s·q^(n+1)}
 * @param drop падение начала отрезка {@code g·B(n)}
 * @param fall вертикальная длина отрезка {@code w(n) = g·S(n+1)}
 */
public record Sweep(int tick, double radius, double step, double drop, double fall) {
    /** Радиус сферы конца отрезка. */
    public double outerRadius() {
        return radius + step;
    }

    /**
     * Коэффициент {@code a = w² − U²} при {@code f²}. При {@code a ≤ 0} сферы вложены, при
     * {@code a > 0} промежуточные сферы выступают за крайние не больше чем на {@code a/4}.
     */
    public double bulge() {
        return fall * fall - step * step;
    }

    /** Квадрат длины вектора {@code v_(n+1)} для направления с вертикальной составляющей {@code uy}. */
    double lengthSqr(double uy) {
        return step * step + fall * fall - 2.0D * step * fall * uy;
    }
}
