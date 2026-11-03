package com.electroradar.cannon.diagnostics;

import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.Sweep;
import com.electroradar.cannon.ballistics.TargetForecast;
import com.electroradar.cannon.ballistics.Vector3;

/** Снимок расчёта в мировых координатах; f задаёт долю sweep, а не время движения цели. */
public record ShotPrediction(ProjectileProfile profile, Vector3 pivot, TargetForecast target, FireSolution solution) {
    public Vector3 targetAt(int flightTick) {
        return pivot.add(target.positionAt(flightTick));
    }

    public Vector3 impact() {
        return targetAt(solution.tick());
    }

    public Vector3 sweepImpact() {
        return sweepPoint(solution.tick(), solution.fraction());
    }

    public Vector3 sweepPoint(int tick, double f) {
        Sweep sweep = FlightCoefficients.of(profile).sweep(tick);
        return pivot.add(solution.direction().scale(sweep.radius() + f * sweep.step()))
                .add(new Vector3(0, -sweep.drop() - f * sweep.fall(), 0));
    }
    public static double distanceToSegment(Vector3 point, Vector3 start, Vector3 end) {
        Vector3 step = end.subtract(start);
        double square = step.dot(step);
        double f = square == 0 ? 0 : Math.max(0, Math.min(1, point.subtract(start).dot(step) / square));
        return point.subtract(start.add(step.scale(f))).length();
    }
}
