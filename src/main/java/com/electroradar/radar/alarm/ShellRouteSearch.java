package com.electroradar.radar.alarm;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;

/**
 * Поиск первого и последнего опасного хода по замкнутой траектории, без потиковой интеграции.
 *
 * <p>При линейном сопротивлении каждая координата концов CBC-отрезка имеет вид
 * a + b·n + c·q^n, без сопротивления — квадратного многочлена. Её дискретное приращение
 * монотонно, поэтому экстремум не более одного. Концы интервала и эти экстремумы ограничивают
 * все отрезки внутри интервала: непересекающийся объём отбрасывается целиком. На монотонных
 * частях время сужается шестью плоскостями зоны, спорные границы проверяются точным slab test.
 * Поворот зоны и равномерное движение берутся из той же модели, что у ZoneVolume.</p>
 */
final class ShellRouteSearch {
    private final HostileShell shell;
    private final ZoneVolume zone;
    private final int last;
    private final boolean fixedWorldZone;
    private final Map<Integer, Sample> samples = new HashMap<>();
    private final TreeSet<Integer> extrema = new TreeSet<>();

    ShellRouteSearch(HostileShell shell, ZoneVolume zone, double reach, int limit) {
        this.shell = shell;
        this.zone = zone.inflate(reach);
        this.last = limit - 1;
        this.fixedWorldZone = zone.frame().identity() && zone.velocity().equals(Vector3.ZERO);
    }

    ShellRoutePrediction predict() {
        if (past(0)) return new ShellRoutePrediction(-1, -1, true);
        if (shell.motion().gravity() == 0 && shell.velocity().dot(shell.velocity()) == 0
                && zone.velocity().dot(zone.velocity()) == 0) {
            boolean inside = hit(0);
            return new ShellRoutePrediction(inside ? 0 : -1, inside ? Integer.MAX_VALUE : -1, true);
        }
        collectExtrema();
        int first = find(0, last, false);
        int end = first < 0 ? -1 : find(first, last, true);
        return new ShellRoutePrediction(first, end, past(last));
    }

    /** В каждой локальной координате приращение имеет вид a + b·S(n), S(n) монотонна. */
    private void collectExtrema() {
        double k = shell.motion().drag();
        double f = 1 - k * .5;
        double collisionFactor = f - k * (1 - k);
        Vector3[] axes = {zone.frame().x(), zone.frame().y(), zone.frame().z()};
        for (Vector3 axis : axes) {
            double norm = axis.dot(axis);
            double v = shell.velocity().dot(axis) / norm;
            double g = -shell.motion().gravity() * axis.y() / norm;
            double moving = zone.velocity().dot(axis) / norm;
            double acceleration = g - k * v;
            turning(f * v + g * .5 - moving, f * acceleration, k);
            turning(collisionFactor * v + g * (1.5 - k) - moving, collisionFactor * acceleration, k);
            turning(f * ((1 - k) * v + g) + g * .5 - moving, f * (1 - k) * acceleration, k);
        }
    }

    /** Находит смену знака приращения двоичным поиском, а не обходом тиков. */
    private void turning(double base, double factor, double drag) {
        double end = base + factor * sum(last, drag);
        if (base >= 0 && end >= 0 || base <= 0 && end <= 0) return;
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            double slope = base + factor * sum(mid, drag);
            if ((slope < 0) == (base < 0)) lo = mid;
            else hi = mid;
        }
        // Соседние точки включены и для нулевого приращения, и для округления формул.
        for (int tick = Math.max(0, lo - 1); tick <= Math.min(last, hi + 2); tick++) extrema.add(tick);
    }

    private static double sum(int n, double drag) {
        return drag == 0 ? n : -Math.expm1(n * Math.log1p(-drag)) / drag;
    }

    private int find(int lo, int hi, boolean reverse) {
        if (lo == hi) return hit(lo) ? lo : -1;
        if (extrema.isEmpty() || extrema.subSet(lo, false, hi, false).isEmpty()) {
            Window window = clip(lo, hi);
            if (window == null) return -1;
            lo = window.lo(); hi = window.hi();
        }
        int edge = reverse ? hi : lo;
        if (hit(edge)) return edge;
        if (lo == hi) return -1;
        Bounds bounds = bounds(lo, hi);
        if (bounds.outside(zone.local())) return -1;
        if (bounds.inside(zone.local())) return reverse ? hi : lo;
        int mid = (lo + hi) >>> 1;
        int result = reverse ? find(mid + 1, hi, true) : find(lo, mid, false);
        return result >= 0 ? result : reverse ? find(lo, mid, true) : find(mid + 1, hi, false);
    }

    /** На монотонной части пересечение шести полупространств сужает время двоичным поиском. */
    private Window clip(int lo, int hi) {
        double[] lower = {zone.local().min().x(), zone.local().min().y(), zone.local().min().z()};
        double[] upper = {zone.local().max().x(), zone.local().max().y(), zone.local().max().z()};
        for (int axis = 0; axis < 3; axis++) {
            double[] a = sample(lo).local(), b = sample(hi).local();
            boolean increasing = true, decreasing = true;
            double magnitude = 0;
            for (int curve = 0; curve < 9; curve += 3) {
                increasing &= a[curve + axis] <= b[curve + axis];
                decreasing &= a[curve + axis] >= b[curve + axis];
                magnitude = Math.max(magnitude, Math.max(Math.abs(a[curve + axis]), Math.abs(b[curve + axis])));
            }
            if (!increasing && !decreasing) continue;
            int sign = increasing ? 1 : -1;
            double padding = 1e-8 + 64 * Math.ulp(magnitude);
            double min = (increasing ? lower[axis] : -upper[axis]) - padding;
            double max = (increasing ? upper[axis] : -lower[axis]) + padding;
            if (extent(hi, axis, true, sign) < min || extent(lo, axis, false, sign) > max) return null;
            int left = lo, right = hi;
            if (extent(left, axis, true, sign) < min) {
                while (right - left > 1) {
                    int mid = (left + right) >>> 1;
                    if (extent(mid, axis, true, sign) < min) left = mid;
                    else right = mid;
                }
                lo = right;
            }
            left = lo; right = hi;
            if (extent(right, axis, false, sign) > max) {
                while (right - left > 1) {
                    int mid = (left + right) >>> 1;
                    if (extent(mid, axis, false, sign) > max) right = mid;
                    else left = mid;
                }
                hi = left;
            }
        }
        return new Window(lo, hi);
    }

    private double extent(int tick, int axis, boolean upper, int sign) {
        double[] p = sample(tick).local();
        double a = sign * p[axis], b = sign * p[axis + 3], c = sign * p[axis + 6];
        return upper ? Math.max(a, Math.max(b, c)) : Math.min(a, Math.min(b, c));
    }

    private record Window(int lo, int hi) { }

    int evaluatedTicks() { return samples.size(); }

    private Bounds bounds(int lo, int hi) {
        Bounds bounds = new Bounds();
        bounds.include(sample(lo).local());
        bounds.include(sample(hi).local());
        if (!extrema.isEmpty())
            for (int tick : extrema.subSet(lo, true, hi, true)) bounds.include(sample(tick).local());
        return bounds;
    }

    private boolean hit(int tick) {
        double[] p = sample(tick).local();
        return zone.local().intersects(p[0], p[1], p[2], p[3], p[4], p[5])
                || zone.local().intersects(p[0], p[1], p[2], p[6], p[7], p[8]);
    }

    private Sample sample(int tick) {
        Sample cached = samples.get(tick);
        if (cached != null) return cached;
        int n = tick;
        HostileShell state = shell.after(n);
        Vector3 start = state.position(), velocity = state.velocity();
        double k = shell.motion().drag();
        double ax = -k * velocity.x(), ay = -k * velocity.y() - shell.motion().gravity(), az = -k * velocity.z();
        Vector3 collision = new Vector3(start.x() + (velocity.x() + ax),
                start.y() + (velocity.y() + ay), start.z() + (velocity.z() + az));
        Vector3 moved = new Vector3(start.x() + velocity.x() + ax * .5,
                start.y() + velocity.y() + ay * .5, start.z() + velocity.z() + az * .5);
        double[] local;
        if (fixedWorldZone) {
            local = new double[]{start.x(), start.y(), start.z(), collision.x(), collision.y(), collision.z(),
                    moved.x(), moved.y(), moved.z()};
        } else {
            local = new double[9];
            local(start, n, local, 0);
            local(collision, n + 1, local, 3);
            local(moved, n + 1, local, 6);
        }
        Sample created = new Sample(start, velocity, collision, local);
        samples.put(tick, created);
        return created;
    }

    private void local(Vector3 point, int tick, double[] result, int offset) {
        ZoneFrame frame = zone.frame();
        double x = point.x() - zone.velocity().x() * tick - frame.worldOrigin().x();
        double y = point.y() - zone.velocity().y() * tick - frame.worldOrigin().y();
        double z = point.z() - zone.velocity().z() * tick - frame.worldOrigin().z();
        result[offset] = frame.localOrigin().x() + project(x, y, z, frame.x());
        result[offset + 1] = frame.localOrigin().y() + project(x, y, z, frame.y());
        result[offset + 2] = frame.localOrigin().z() + project(x, y, z, frame.z());
        if (!Double.isFinite(result[offset]) || !Double.isFinite(result[offset + 1]) || !Double.isFinite(result[offset + 2]))
            throw new IllegalArgumentException("Non-finite local forecast");
    }

    private static double project(double x, double y, double z, Vector3 axis) {
        return (x * axis.x() + y * axis.y() + z * axis.z()) / axis.dot(axis);
    }

    private boolean past(int tick) {
        Sample s = sample(tick);
        Vector3 start = s.start().subtract(zone.velocity().scale(tick));
        Vector3 end = s.collision().subtract(zone.velocity().scale(tick + 1));
        Vector3 v = s.velocity().subtract(zone.velocity());
        ProtectionBox box = zone.worldBounds();
        double drag = shell.motion().drag();
        return ShellRoutePrediction.past(start.x(), end.x(), v.x(),
                ShellRoutePrediction.limitX(s.velocity().x(), drag, zone.velocity().x()), box.min().x(), box.max().x())
                || ShellRoutePrediction.past(start.z(), end.z(), v.z(),
                ShellRoutePrediction.limitX(s.velocity().z(), drag, zone.velocity().z()), box.min().z(), box.max().z())
                || ShellRoutePrediction.past(start.y(), end.y(), v.y(),
                ShellRoutePrediction.limitY(s.velocity().y(), drag, shell.motion().gravity(), zone.velocity().y()), box.min().y(), box.max().y());
    }

    private record Sample(Vector3 start, Vector3 velocity, Vector3 collision, double[] local) { }

    private static final class Bounds {
        final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        void include(double[] points) {
            for (int i = 0; i < points.length; i++) {
                int axis = i % 3;
                min[axis] = Math.min(min[axis], points[i]);
                max[axis] = Math.max(max[axis], points[i]);
            }
        }
        double padding(int axis) { return 1e-8 + 64 * Math.ulp(Math.max(Math.abs(min[axis]), Math.abs(max[axis]))); }
        boolean outside(ProtectionBox box) {
            return max[0] + padding(0) < box.min().x() || min[0] - padding(0) > box.max().x()
                    || max[1] + padding(1) < box.min().y() || min[1] - padding(1) > box.max().y()
                    || max[2] + padding(2) < box.min().z() || min[2] - padding(2) > box.max().z();
        }
        boolean inside(ProtectionBox box) {
            return min[0] - padding(0) >= box.min().x() && max[0] + padding(0) <= box.max().x()
                    && min[1] - padding(1) >= box.min().y() && max[1] + padding(1) <= box.max().y()
                    && min[2] - padding(2) >= box.min().z() && max[2] + padding(2) <= box.max().z();
        }
    }
}
