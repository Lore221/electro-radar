package com.electroradar.radar.network;

/** Независимые категории наблюдаемой цели; одна цель может входить в несколько категорий. */
public enum TargetCategory {
    PROJECTILE,
    /** Все снаряды больших пушек, включая ядра; конкретные классы определяет адаптер. */
    SHELL,
    PLAYER,
    /** Категория спавна MONSTER, а не текущая агрессия конкретного моба. */
    HOSTILE_MOB,
    /** Мобы остальных категорий спавна, кроме MISC. */
    PASSIVE_MOB,
    MOVING_STRUCTURE,
    OTHER
}
