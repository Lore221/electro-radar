package com.electroradar.radar.alarm;

import com.electroradar.cannon.ballistics.Vector3;

/** Геометрия зоны без зависимости от мира: касание границы также считается пересечением. */
public record ProtectionBox(Vector3 min, Vector3 max) {
    public ProtectionBox {
        if (min == null || max == null || min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z()) {
            throw new IllegalArgumentException("Invalid protection box");
        }
    }

    public ProtectionBox inflate(double reach) {
        if (!(reach >= 0) || !Double.isFinite(reach)) throw new IllegalArgumentException("Invalid reach");
        Vector3 padding = new Vector3(reach, reach, reach);
        return new ProtectionBox(min.subtract(padding), max.add(padding));
    }

    /** Slab test: проверяется весь отрезок, в том числе начало внутри и нулевой отрезок. */
    public boolean intersects(Vector3 start, Vector3 end) {
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double a = axis == 0 ? start.x() : axis == 1 ? start.y() : start.z();
            double b = axis == 0 ? end.x() : axis == 1 ? end.y() : end.z();
            double lo = axis == 0 ? min.x() : axis == 1 ? min.y() : min.z();
            double hi = axis == 0 ? max.x() : axis == 1 ? max.y() : max.z();
            double step = b - a;
            if (step == 0) {
                if (a < lo || a > hi) return false;
            } else {
                double first = (lo - a) / step;
                double last = (hi - a) / step;
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
                if (enter > exit) return false;
            }
        }
        return true;
    }
}
