package com.electroradar.radar.network;

import java.util.Objects;
import java.util.UUID;

/** Идентификатор сети, который сервер создаёт и сохраняет в привязанных приборах. */
public record NetworkId(UUID value) {
    public NetworkId {
        Objects.requireNonNull(value, "value");
    }

    /** Создаёт новый случайный идентификатор для ещё не привязанной сети. */
    public static NetworkId create() {
        return new NetworkId(UUID.randomUUID());
    }
}
