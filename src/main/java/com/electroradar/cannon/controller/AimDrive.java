package com.electroradar.cannon.controller;

/**
 * Привод наведения: за тик поворачивает установку к требуемым углам, ограничивая угловую скорость
 * и ускорение. Сам привод не знает об орудии и цели — он получает текущие и требуемые углы.
 *
 * <p>Целевая скорость — пропорциональная ошибке с прямой связью по скорости изменения требуемого
 * угла: без неё привод всегда отставал бы от движущейся цели. Шаг не перескакивает требуемый угол.
 * Привод считается успокоившимся, когда он почти стоит или устойчиво следует за целью.</p>
 */
final class AimDrive {
    /** Доля ошибки, которую привод стремится отработать за тик. */
    private static final double RESPONSE_PER_TICK = 0.35D;
    /** Ниже этой угловой скорости привод считается остановившимся, градусов за тик. */
    private static final double SETTLED_SPEED = 1.0D;
    private static final double FEED_FORWARD_SMOOTHING = 0.65D;
    /** Перерыв, после которого прямая связь начинается заново. */
    private static final long MAX_FEED_FORWARD_GAP_TICKS = 5L;

    private double yawVelocity;
    private double pitchVelocity;
    private Object feedForwardKey;
    private long lastTick = Long.MIN_VALUE;
    private double lastDesiredYaw;
    private double lastDesiredPitch;
    private double yawFeedForward;
    private double pitchFeedForward;

    /**
     * Делает один шаг привода.
     *
     * @param key идентичность сопровождаемой цели; смена сбрасывает прямую связь
     * @param maxSpeed наибольшая угловая скорость, градусов за тик
     * @param acceleration наибольшее изменение скорости за тик, градусов за тик²
     */
    Step step(Object key, long tick, double currentYaw, double currentPitch,
              double desiredYaw, double desiredPitch, double maxSpeed, double acceleration) {
        boolean tracking = updateFeedForward(key, tick, desiredYaw, desiredPitch, maxSpeed);
        double yawError = wrapDegrees(desiredYaw - currentYaw);
        double pitchError = desiredPitch - currentPitch;
        double yawTarget = clamp(clamp(yawError * RESPONSE_PER_TICK, maxSpeed) + yawFeedForward, maxSpeed);
        double pitchTarget = clamp(clamp(pitchError * RESPONSE_PER_TICK, maxSpeed) + pitchFeedForward, maxSpeed);
        yawVelocity = approach(yawVelocity, yawTarget, acceleration);
        pitchVelocity = approach(pitchVelocity, pitchTarget, acceleration);
        double yawStep = clamp(yawVelocity, Math.abs(yawError));
        double pitchStep = clamp(pitchVelocity, Math.abs(pitchError));
        double trackingTolerance = Math.max(0.05D, 2.0D * acceleration);
        boolean stopped = Math.abs(yawVelocity) <= SETTLED_SPEED && Math.abs(pitchVelocity) <= SETTLED_SPEED;
        boolean following = tracking && Math.abs(yawVelocity - yawTarget) <= trackingTolerance
                && Math.abs(pitchVelocity - pitchTarget) <= trackingTolerance;
        return new Step(currentYaw + yawStep, currentPitch + pitchStep, stopped || following);
    }

    /** Останавливает привод, например при потере цели или орудия. */
    void stop() {
        yawVelocity = 0.0D;
        pitchVelocity = 0.0D;
        feedForwardKey = null;
        lastTick = Long.MIN_VALUE;
        yawFeedForward = 0.0D;
        pitchFeedForward = 0.0D;
    }

    /** @return true, если прямая связь уже оценена по предыдущему тику той же цели */
    private boolean updateFeedForward(Object key, long tick, double desiredYaw, double desiredPitch, double maxSpeed) {
        boolean continuous = key != null && key.equals(feedForwardKey)
                && tick > lastTick && tick - lastTick <= MAX_FEED_FORWARD_GAP_TICKS;
        if (continuous) {
            double elapsed = tick - lastTick;
            double yawRate = wrapDegrees(desiredYaw - lastDesiredYaw) / elapsed;
            double pitchRate = (desiredPitch - lastDesiredPitch) / elapsed;
            yawFeedForward = clamp(yawFeedForward + (yawRate - yawFeedForward) * FEED_FORWARD_SMOOTHING, maxSpeed);
            pitchFeedForward = clamp(pitchFeedForward + (pitchRate - pitchFeedForward) * FEED_FORWARD_SMOOTHING, maxSpeed);
        } else {
            feedForwardKey = key;
            yawFeedForward = 0.0D;
            pitchFeedForward = 0.0D;
        }
        lastTick = tick;
        lastDesiredYaw = desiredYaw;
        lastDesiredPitch = desiredPitch;
        return continuous;
    }

    /** Обороты в минуту — в градусы за тик: {@code rpm·360/60/20}. */
    static double degreesPerTick(double rpm) {
        return rpm * 0.3D;
    }

    /** Обороты в минуту за секунду — в градусы за тик²: ещё одно деление на 20. */
    static double degreesPerTickSquared(double rpmPerSecond) {
        return rpmPerSecond * 0.3D / 20.0D;
    }

    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static double approach(double current, double target, double maxDelta) {
        return current < target ? Math.min(target, current + maxDelta) : Math.max(target, current - maxDelta);
    }

    /**
     * Углы после шага и состояние привода.
     *
     * @param settled привод почти стоит или устойчиво следует за целью — выстрел не смажется
     */
    record Step(double yaw, double pitch, boolean settled) {
    }
}
