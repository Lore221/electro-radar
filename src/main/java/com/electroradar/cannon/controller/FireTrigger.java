package com.electroradar.cannon.controller;

import java.util.Objects;

import com.electroradar.cannon.mount.CannonKind;

/**
 * Разрешение попытки прямого выстрела: большая пушка при непрерывной готовности раз в 5 тиков,
 * автопушка с коротким удержанием очереди. Реальный темп и готовность казённика проверяет CBC.
 */
final class FireTrigger {
    static final int BIG_CANNON_ATTEMPT_INTERVAL_TICKS = 5;
    static final int AUTOCANNON_GRACE_TICKS = 20;

    private boolean output;
    private int pulseCooldown;
    private int graceTicks;

    /**
     * Вычисляет разрешение попытки выстрела этого тика.
     *
     * @param ready все условия выстрела выполнены
     * @param targetHeld цель по-прежнему сопровождается и достижима, хотя готовность могла пропасть
     * @return true — разрешить попытку в этом тике
     */
    boolean update(CannonKind kind, boolean ready, boolean targetHeld) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.controllerFires()) {
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
            pulseCooldown = BIG_CANNON_ATTEMPT_INTERVAL_TICKS - 1;
            output = true;
        }
        return output;
    }

    /** Проверка перед изменением таймеров: нужна ли проверка траектории для попытки этого тика. */
    boolean wouldFire(CannonKind kind, boolean ready, boolean targetHeld) {
        if (!kind.controllerFires()) return false;
        if (kind == CannonKind.AUTOCANNON) return ready || output && targetHeld && graceTicks > 0;
        return ready && pulseCooldown <= 0;
    }

    void reset() {
        output = false;
        pulseCooldown = 0;
        graceTicks = 0;
    }
}
