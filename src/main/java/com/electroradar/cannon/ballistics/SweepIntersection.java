package com.electroradar.cannon.ballistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Точное ядро попадания в один тик (B1′): направление, при котором отрезок {@link Sweep} проходит
 * через заданную точку.
 *
 * <p>Пусть точка {@code (x, y, z)} и {@code h = y + drop}. Отрезок проходит через неё при доле
 * {@code f}, если {@code F(f) = x² + z² + (h + f·fall)² − (radius + f·step)² = 0}, то есть
 * {@code a·f² + b·f + c = 0} с {@code a = fall² − step²}, {@code b = 2·(h·fall − radius·step)},
 * {@code c = x² + z² + h² − radius²}. Тогда {@code u = (x, h + f·fall, z)/(radius + f·step)}.</p>
 *
 * <p>Ветвь корня определяется знаком перед {@code √D}: в корне {@code 2a·f + b = ∓√D}, а это
 * знак {@code ∂F/∂f}, то есть ветвь {@link ArcFamily}. Никаких дополнительных вычислений для
 * разделения LOW/HIGH не требуется.</p>
 */
public final class SweepIntersection {
    /** CBC не проверяет столкновения на отрезке короче 0,01 блока ({@code velMag·t < 1e-2}). */
    static final double MIN_SWEEP_LENGTH = 1.0E-2D;
    private static final double MIN_SWEEP_LENGTH_SQR = MIN_SWEEP_LENGTH * MIN_SWEEP_LENGTH;
    /** Допуск доли отрезка: корни на границе отрезка не теряются из-за округления. */
    private static final double FRACTION_TOLERANCE = 1.0E-9D;
    /** Относительный порог, ниже которого {@code a} или {@code b} считаются нулём. */
    private static final double DEGENERATE_TOLERANCE = 1.0E-14D;
    /** Относительный порог дискриминанта, внутри которого корень считается двойным. */
    private static final double TANGENT_TOLERANCE = 1.0E-12D;
    /** Допуск {@code ||u|² − 1|} для корня, восстановленного с округлением. */
    private static final double DIRECTION_TOLERANCE = 1.0E-7D;
    /** Корни ближе этого по {@code f} — один и тот же корень, зажатый к границе отрезка. */
    private static final double DUPLICATE_FRACTION = 1.0E-8D;

    private SweepIntersection() {
    }

    /**
     * Все допустимые направления на точку {@code target} в тик {@code sweep.tick()}, не больше двух.
     * Пустой список означает отсутствие попадания только в этом тике.
     */
    public static List<FireSolution> solve(Sweep sweep, Vector3 target, AimConstraints constraints) {
        Objects.requireNonNull(sweep, "sweep");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(constraints, "constraints");
        double height = target.y() + sweep.drop();
        double horizontal = target.x() * target.x() + target.z() * target.z();
        double a = sweep.bulge();
        double b = 2.0D * (height * sweep.fall() - sweep.radius() * sweep.step());
        double c = horizontal + height * height - sweep.radius() * sweep.radius();
        double scale = Math.max(1.0D, Math.max(Math.abs(a), Math.max(Math.abs(b), Math.abs(c))));

        List<FireSolution> solutions = new ArrayList<>(2);
        if (Math.abs(a) < DEGENERATE_TOLERANCE * scale) {
            if (Math.abs(b) >= DEGENERATE_TOLERANCE * scale) {
                accept(solutions, sweep, target, height, constraints, -c / b, b < 0.0D ? ArcBranch.LOW : ArcBranch.HIGH);
            }
            return solutions;
        }
        double discriminant = b * b - 4.0D * a * c;
        double discriminantScale = Math.max(1.0D, Math.max(b * b, Math.abs(4.0D * a * c)));
        if (discriminant < -TANGENT_TOLERANCE * discriminantScale) return solutions;
        if (discriminant <= TANGENT_TOLERANCE * discriminantScale) {
            accept(solutions, sweep, target, height, constraints, -b / (2.0D * a), ArcBranch.TANGENT);
            return solutions;
        }
        // Устойчивая форма: q/a и c/q без вычитания близких чисел. При b ≥ 0 q/a = (−b − √D)/(2a).
        double q = -0.5D * (b + Math.copySign(Math.sqrt(discriminant), b));
        double low = b >= 0.0D ? q / a : c / q;
        double high = b >= 0.0D ? c / q : q / a;
        accept(solutions, sweep, target, height, constraints, low, ArcBranch.LOW);
        accept(solutions, sweep, target, height, constraints, high, ArcBranch.HIGH);
        return solutions;
    }

    private static void accept(List<FireSolution> solutions, Sweep sweep, Vector3 target, double height,
                               AimConstraints constraints, double rawFraction, ArcBranch branch) {
        if (!(rawFraction >= -FRACTION_TOLERANCE && rawFraction <= 1.0D + FRACTION_TOLERANCE)) return;
        if (!constraints.allowsBranch(branch)) return;
        double fraction = Math.min(1.0D, Math.max(0.0D, rawFraction));
        for (FireSolution existing : solutions) {
            if (Math.abs(existing.fraction() - fraction) <= DUPLICATE_FRACTION) return;
        }
        double reach = sweep.radius() + fraction * sweep.step();
        if (!(reach > 0.0D)) return;
        double ux = target.x() / reach;
        double uy = (height + fraction * sweep.fall()) / reach;
        double uz = target.z() / reach;
        double norm = ux * ux + uy * uy + uz * uz;
        if (Math.abs(norm - 1.0D) > DIRECTION_TOLERANCE) return;
        double inverse = 1.0D / Math.sqrt(norm);
        ux *= inverse;
        uy *= inverse;
        uz *= inverse;
        if (sweep.lengthSqr(uy) < MIN_SWEEP_LENGTH_SQR) return;
        double pitch = Math.toDegrees(Math.atan2(uy, Math.hypot(ux, uz)));
        if (!constraints.allowsPitch(pitch)) return;
        double yaw = Math.toDegrees(Math.atan2(-ux, uz));
        solutions.add(new FireSolution(sweep.tick(), fraction, new Vector3(ux, uy, uz), pitch, yaw, branch));
    }
}
