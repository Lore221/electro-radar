package com.electroradar.cannon.intercept;

import java.util.Objects;
import java.util.Optional;

import com.electroradar.cannon.ballistics.Vector3;

/**
 * Логика командно-неконтактного взрывателя перехватчика без зависимостей от Minecraft.
 *
 * <p>Штатный неконтактный взрыватель CBC ищет цели через {@code canHitEntity}, а он отбрасывает
 * любые {@code Projectile}: чужой снаряд для него невидим. Поэтому взрыватель свой. Пушка
 * программирует его при выстреле ({@link Program}): он взводится только в окне вокруг расчётного
 * тика встречи и подрывает снаряд самоликвидацией после окна, чтобы промах не падал на своих.</p>
 *
 * <p>В отрезке столкновений тика {@code n} цель уже сделала свой ход и неподвижна
 * (см. {@link HostileShell}), поэтому достаточно расстояния от точки до отрезка. Осколки CBC
 * летят конусом вперёд по скорости снаряда, и подрыв в точке наибольшего сближения оставил бы
 * цель сбоку от конуса. Взрыватель отступает назад по отрезку на {@code leadDistance}: цель
 * оказывается на оси конуса впереди точки подрыва ({@link FragmentKillModel}).</p>
 */
public final class InterceptFuze {
    private InterceptFuze() {
    }

    /**
     * Программа, записываемая при выстреле.
     *
     * @param interceptTick расчётный тик встречи (номер отрезка перехватчика)
     * @param armTick первый тик, в котором взрыватель реагирует на цель
     * @param selfDestructTick тик самоликвидации, если цель так и не встретилась
     * @param proximityRadius расстояние от цели до отрезка, при котором взрыватель срабатывает
     * @param leadDistance насколько раньше точки сближения подрывать, блоки
     */
    public record Program(int interceptTick, int armTick, int selfDestructTick, double proximityRadius,
                          double leadDistance) {
        public Program {
            if (interceptTick < 0 || armTick < 0 || armTick > interceptTick || selfDestructTick <= interceptTick
                    || !(proximityRadius > 0.0D) || !(leadDistance >= 0.0D)
                    || !Double.isFinite(proximityRadius) || !Double.isFinite(leadDistance)) {
                throw new IllegalArgumentException("Invalid fuze program");
            }
        }

        /** Симметричное окно {@code ±halfWindow} тиков вокруг встречи. */
        public static Program around(int interceptTick, int halfWindow, double proximityRadius, double leadDistance) {
            if (halfWindow < 0) throw new IllegalArgumentException("halfWindow must be non-negative");
            return new Program(interceptTick, Math.max(0, interceptTick - halfWindow), interceptTick + halfWindow + 1,
                    proximityRadius, leadDistance);
        }

        public boolean armed(int flightTick) {
            return flightTick >= armTick && flightTick < selfDestructTick;
        }

        public boolean selfDestruct(int flightTick) {
            return flightTick >= selfDestructTick;
        }
    }

    /**
     * Решение взрывателя в отрезке {@code start → end} тика {@code flightTick}.
     *
     * @param target положение цели в этом тике (она уже сделала ход)
     * @return точка подрыва, если цель в радиусе срабатывания
     */
    public static Optional<Detonation> evaluate(Program program, int flightTick, Vector3 start, Vector3 end,
                                                Vector3 target) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(target, "target");
        if (!program.armed(flightTick)) return Optional.empty();
        Vector3 segment = end.subtract(start);
        double length = segment.length();
        double along = length < 1.0E-9D ? 0.0D
                : Math.max(0.0D, Math.min(length, target.subtract(start).dot(segment) / length));
        Vector3 axis = length < 1.0E-9D ? Vector3.ZERO : segment.scale(1.0D / length);
        double miss = target.subtract(start.add(axis.scale(along))).length();
        if (miss > program.proximityRadius()) return Optional.empty();
        // Отступ не выходит за начало отрезка: точку прошлого тика цель уже покинула.
        Vector3 point = start.add(axis.scale(Math.max(0.0D, along - program.leadDistance())));
        return Optional.of(new Detonation(point, miss, target.subtract(point)));
    }

    /**
     * @param point точка подрыва
     * @param miss расстояние от цели до отрезка в момент срабатывания
     * @param relative положение цели относительно точки подрыва — вход {@link FragmentKillModel}
     */
    public record Detonation(Vector3 point, double miss, Vector3 relative) {
    }
}
