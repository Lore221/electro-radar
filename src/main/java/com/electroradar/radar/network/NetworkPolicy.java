package com.electroradar.radar.network;

/** Сетевая политика памяти; длительность секторного окна теперь задаёт общий серверный конфиг. */
public record NetworkPolicy(long forgetAfterMisses) {
    public NetworkPolicy {
        if (forgetAfterMisses <= 0) {
            throw new IllegalArgumentException("forgetAfterMisses must be positive");
        }
    }
}
