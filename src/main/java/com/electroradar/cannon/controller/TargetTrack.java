package com.electroradar.cannon.controller;

import java.util.Objects;

import com.electroradar.cannon.ballistics.Vector3;

/**
 * Оценка скорости и ускорения одной цели по её положениям в последовательных тиках.
 *
 * <p>Скорость берётся как смещение за тик, а не из {@code getDeltaMovement}: у игроков на сервере
 * оно не отражает движение клиента. Ускорение сглаживается и ограничивается, чтобы телепортация
 * или первый неполный кадр не дали ложный рывок; в прогноз оно входит только после прогрева
 * захвата. У стоящей на земле цели вертикальные составляющие обнуляются: Minecraft держит у неё
 * небольшую отрицательную скорость прижатия.</p>
 */
final class TargetTrack {
    /** Сколько тиков подряд цель должна наблюдаться, прежде чем учитывать её ускорение. */
    static final int ACCELERATION_WARMUP_TICKS = 3;
    /** Перерыв, после которого история считается разорванной. */
    private static final long MAX_SAMPLE_GAP_TICKS = 5L;
    private static final double ACCELERATION_SMOOTHING = 0.65D;
    private static final double MAX_ACCELERATION = 0.25D;

    private Object targetKey;
    private long lastTick = Long.MIN_VALUE;
    private Vector3 lastPosition;
    private Vector3 velocity = Vector3.ZERO;
    private Vector3 acceleration = Vector3.ZERO;
    private boolean accelerationKnown;
    private int lockTicks;

    /**
     * Добавляет положение цели в тик {@code tick} и возвращает текущую оценку движения.
     *
     * @param targetKey идентичность цели; смена начинает историю заново
     * @param reportedVelocity скорость, сообщённая игрой, — используется только для первого кадра
     */
    Estimate sample(Object targetKey, long tick, Vector3 position, Vector3 reportedVelocity, boolean onGround) {
        Objects.requireNonNull(targetKey, "targetKey");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(reportedVelocity, "reportedVelocity");
        if (!targetKey.equals(this.targetKey) || lastPosition == null
                || tick <= lastTick || tick - lastTick > MAX_SAMPLE_GAP_TICKS) {
            this.targetKey = targetKey;
            velocity = grounded(reportedVelocity, onGround);
            acceleration = Vector3.ZERO;
            accelerationKnown = false;
            lockTicks = 0;
        } else {
            double elapsed = tick - lastTick;
            Vector3 measured = grounded(position.subtract(lastPosition).scale(1.0D / elapsed), onGround);
            Vector3 raw = clamp(measured.subtract(velocity).scale(1.0D / elapsed));
            acceleration = accelerationKnown
                    ? acceleration.add(raw.subtract(acceleration).scale(ACCELERATION_SMOOTHING)) : raw;
            accelerationKnown = true;
            velocity = measured;
            lockTicks = Math.min(lockTicks + 1, 1_000_000);
        }
        lastTick = tick;
        lastPosition = position;
        Vector3 usedAcceleration = lockTicks >= ACCELERATION_WARMUP_TICKS ? grounded(acceleration, onGround) : Vector3.ZERO;
        return new Estimate(position, velocity, usedAcceleration, lockTicks);
    }

    void reset() {
        targetKey = null;
        lastTick = Long.MIN_VALUE;
        lastPosition = null;
        velocity = Vector3.ZERO;
        acceleration = Vector3.ZERO;
        accelerationKnown = false;
        lockTicks = 0;
    }

    private static Vector3 grounded(Vector3 vector, boolean onGround) {
        return onGround ? new Vector3(vector.x(), 0.0D, vector.z()) : vector;
    }

    private static Vector3 clamp(Vector3 vector) {
        return new Vector3(clamp(vector.x()), clamp(vector.y()), clamp(vector.z()));
    }

    private static double clamp(double value) {
        return Math.max(-MAX_ACCELERATION, Math.min(MAX_ACCELERATION, value));
    }

    /**
     * Движение цели в тик последнего измерения.
     *
     * @param lockTicks сколько тиков подряд цель сопровождается без разрыва
     */
    record Estimate(Vector3 position, Vector3 velocity, Vector3 acceleration, int lockTicks) {
        /** Положение через {@code ticks} тиков при постоянном ускорении. */
        Vector3 positionAfter(double ticks) {
            return position.add(velocity.scale(ticks)).add(acceleration.scale(0.5D * ticks * ticks));
        }
    }
}
