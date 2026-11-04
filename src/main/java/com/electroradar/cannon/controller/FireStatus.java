package com.electroradar.cannon.controller;

import java.util.Locale;

/**
 * Главная причина, по которой контроллер сейчас стреляет или не стреляет. Порядок проверок задают
 * {@link TargetControllerBlockEntity} и {@link InterceptControllerBlockEntity}; здесь только
 * устойчивые ключи перевода для очков Create.
 */
public enum FireStatus {
    READY,
    AIMING,
    TARGET_LOCKING,
    NO_NETWORK,
    NO_TARGET,
    TARGET_LOST,
    TARGET_UNSUPPORTED,
    NO_CANNON,
    WRONG_SIDE,
    CANNON_BUSY,
    NO_AMMUNITION,
    UNSUPPORTED_MUNITION,
    TARGET_TOO_CLOSE,
    TARGET_UNREACHABLE,
    TRAJECTORY_BLOCKED,
    TRAJECTORY_UNLOADED,
    /** Перехватчику не выбран снаряд на мониторе, и Shell Alarm сети не видят угроз. */
    NO_THREAT,
    /** Перехватчику нужна автопушка. */
    WRONG_CANNON,
    /** Перехватчику нужны зенитные патроны. */
    FLAK_REQUIRED,
    /** В зенитном патроне нет взрывателя перехвата: программировать нечего. */
    INTERCEPT_FUZE_REQUIRED,
    /** Встреча возможна позже: установка заранее разворачивается к первому выстрелу окна. */
    WINDOW_PENDING,
    /** Встречи есть, но установка не успевает развернуться до их окончания. */
    TOO_LATE;

    public String translationKey() {
        return "goggles.electroradar.target_controller.status." + name().toLowerCase(Locale.ROOT);
    }
}
