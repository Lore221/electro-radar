package com.electroradar.cannon.intercept;

/**
 * Оценка времени разворота установки по каждой оси отдельно: разгон с ускорением {@code α} до
 * скорости {@code ω}, движение и торможение (bang-bang) из покоя. Оси вращаются одновременно,
 * поэтому время разворота — наибольшее из двух, плюс {@code settleTicks} на дотягивание
 * пропорционального привода. Это оценка для планирования, а не обещание: готовность к огню
 * по-прежнему проверяется по фактическому направлению ствола.
 *
 * @param maxSpeed наибольшая угловая скорость {@code ω}, градусов за тик
 * @param acceleration наибольшее угловое ускорение {@code α}, градусов за тик²
 * @param settleTicks запас на успокоение привода, тики
 */
public record SlewModel(double maxSpeed, double acceleration, int settleTicks) {
    public SlewModel {
        if (!(maxSpeed > 0.0D) || !(acceleration > 0.0D) || settleTicks < 0
                || !Double.isFinite(maxSpeed) || !Double.isFinite(acceleration)) {
            throw new IllegalArgumentException("Invalid slew model");
        }
    }

    /** Тики, за которые установка из покоя довернёт с {@code (yawFrom, pitchFrom)} на {@code (yawTo, pitchTo)}. */
    public int ticksToTurn(double yawFrom, double pitchFrom, double yawTo, double pitchTo) {
        double yaw = Math.abs(wrapDegrees(yawTo - yawFrom));
        double pitch = Math.abs(pitchTo - pitchFrom);
        return (int) Math.ceil(Math.max(axisTicks(yaw), axisTicks(pitch)) - 1.0E-9D) + settleTicks;
    }

    /** Успевает ли установка вести огонь, если требуемое направление смещается на {@code degrees} за {@code ticks}. */
    public boolean canTrack(double degrees, int ticks) {
        return ticks > 0 && Math.abs(degrees) <= maxSpeed * ticks;
    }

    /** {@code 2·√(θ/α)} без выхода на полку, иначе {@code θ/ω + ω/α}. */
    private double axisTicks(double angle) {
        if (angle <= 0.0D) return 0.0D;
        double ramp = maxSpeed * maxSpeed / acceleration;
        return angle <= ramp ? 2.0D * Math.sqrt(angle / acceleration) : angle / maxSpeed + maxSpeed / acceleration;
    }

    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }
}
