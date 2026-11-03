package com.electroradar.cannon.ballistics;

import java.util.Objects;

/**
 * Прогноз цели в тиках полёта снаряда: {@code X(n) = p + v·n + a·n²/2}.
 *
 * <p>Координаты задаются относительно центра пушки на момент запуска. Возраст наблюдения, задержка
 * запуска и фаза обновления сущностей должны быть учтены адаптером до построения прогноза.
 * В течение одного отрезка столкновений цель считается неподвижной в точке {@code X(n)}.</p>
 *
 * @param position положение в тик запуска, блоки
 * @param velocity скорость, блоков за тик
 * @param acceleration ускорение, блоков за тик²
 */
public record TargetForecast(Vector3 position, Vector3 velocity, Vector3 acceleration) {
    public TargetForecast {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(acceleration, "acceleration");
    }

    public static TargetForecast stationary(Vector3 position) {
        return new TargetForecast(position, Vector3.ZERO, Vector3.ZERO);
    }

    public Vector3 positionAt(int tick) {
        double n = tick;
        return new Vector3(
                position.x() + n * (velocity.x() + 0.5D * n * acceleration.x()),
                position.y() + n * (velocity.y() + 0.5D * n * acceleration.y()),
                position.z() + n * (velocity.z() + 0.5D * n * acceleration.z()));
    }

    /** Верхняя граница скорости цели на {@code [0, tick]}: {@code |v| + |a|·n}. */
    double speedBound(int tick) {
        return velocity.length() + acceleration.length() * tick;
    }
}
