package com.electroradar.radar.network;

/** Общая для радаров сети длительность окна и предел пропусков завершённых проходов. */
public record NetworkPolicy(long periodTicks, long forgetAfterMisses) {
    public NetworkPolicy {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks must be positive");
        }
        if (forgetAfterMisses <= 0) {
            throw new IllegalArgumentException("forgetAfterMisses must be positive");
        }
    }
}
