package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.function.Supplier;

import com.electroradar.cannon.mount.CannonMunition;

/** Кеш одной собранной пушки. Сброс не использует специальные значения времени. */
final class CannonMunitionCache {
    private static final long REFRESH_TICKS = 20;
    private Object cannon;
    private double spawnOffset;
    private long sampledAt;
    private CannonMunition value;

    CannonMunition read(Object cannon, double spawnOffset, long now, boolean fresh,
                        Supplier<CannonMunition> reader) {
        Objects.requireNonNull(cannon, "cannon");
        if (fresh || value == null || this.cannon != cannon || this.spawnOffset != spawnOffset
                || now < sampledAt || now - sampledAt >= REFRESH_TICKS) {
            value = Objects.requireNonNull(reader.get(), "munition");
            this.cannon = cannon;
            this.spawnOffset = spawnOffset;
            sampledAt = now;
        }
        return value;
    }

    void invalidate() {
        value = null;
        cannon = null;
    }
}
