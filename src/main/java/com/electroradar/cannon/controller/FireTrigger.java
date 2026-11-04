package com.electroradar.cannon.controller;

import java.util.Objects;

import com.electroradar.cannon.mount.CannonKind;

/**
 * Форма выходного сигнала выстрела.
 *
 * <p>Большая пушка CBC стреляет по фронту сигнала, поэтому, пока условия выполнены, контроллер
 * выдаёт короткий импульс раз в {@link #BIG_CANNON_PULSE_INTERVAL_TICKS} тиков. Автопушка стреляет,
 * пока сигнал есть: он держится непрерывно и ещё {@link #AUTOCANNON_GRACE_TICKS} тиков после
 * кратковременной потери готовности, чтобы очередь не рвалась от колебаний привода. Если же цель
 * потеряна или недостижима, сигнал снимается сразу.</p>
 */
final class FireTrigger {
    static final int BIG_CANNON_PULSE_INTERVAL_TICKS = 5;
    static final int AUTOCANNON_GRACE_TICKS = 20;

    private boolean output;
    private int pulseCooldown;
    private int graceTicks;

    /**
     * Вычисляет сигнал этого тика.
     *
     * @param ready все условия выстрела выполнены
     * @param targetHeld цель по-прежнему сопровождается и достижима, хотя готовность могла пропасть
     * @return true — подать сигнал в этом тике
     */
    boolean update(CannonKind kind, boolean ready, boolean targetHeld) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.requiresFireSignal()) {
            reset();
            return false;
        }
        if (kind == CannonKind.AUTOCANNON) {
            pulseCooldown = 0;
            if (ready) {
                graceTicks = AUTOCANNON_GRACE_TICKS;
                output = true;
            } else if (output && targetHeld && graceTicks > 0) {
                graceTicks--;
            } else {
                graceTicks = 0;
                output = false;
            }
            return output;
        }
        graceTicks = 0;
        if (!ready) {
            pulseCooldown = 0;
            output = false;
        } else if (pulseCooldown > 0) {
            pulseCooldown--;
            output = false;
        } else {
            pulseCooldown = BIG_CANNON_PULSE_INTERVAL_TICKS - 1;
            output = true;
        }
        return output;
    }

    /** Проверка перед изменением таймеров: нужна ли проверка траектории для сигнала этого тика. */
    boolean wouldFire(CannonKind kind, boolean ready, boolean targetHeld) {
        if (!kind.requiresFireSignal()) return false;
        if (kind == CannonKind.AUTOCANNON) return ready || output && targetHeld && graceTicks > 0;
        return ready && pulseCooldown <= 0;
    }

    void reset() {
        output = false;
        pulseCooldown = 0;
        graceTicks = 0;
    }
}
