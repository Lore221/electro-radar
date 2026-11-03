package com.electroradar.cannon.ballistics;

import java.util.Objects;

/**
 * Направление выстрела, при котором отрезок столкновений тика {@code tick} проходит через прогнозную
 * точку цели.
 *
 * @param tick тик полёта, в который происходит попадание
 * @param fraction доля отрезка {@code f ∈ [0, 1]} в точке попадания (геометрическая, не время)
 * @param direction единичное направление ствола
 * @param pitch угол места в градусах, положительный вверх
 * @param yaw рыскание в градусах в соглашении Minecraft {@code atan2(−x, z)}
 * @param branch ветвь корня
 */
public record FireSolution(int tick, double fraction, Vector3 direction, double pitch, double yaw,
                           ArcBranch branch) {
    public FireSolution {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(branch, "branch");
        if (tick < 0 || !(fraction >= 0.0D && fraction <= 1.0D)) {
            throw new IllegalArgumentException("Invalid fire solution");
        }
    }
}
