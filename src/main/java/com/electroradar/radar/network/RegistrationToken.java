package com.electroradar.radar.network;

import java.util.Objects;
import java.util.UUID;

/** Непредсказуемый токен одной конкретной загрузки endpoint. */
public record RegistrationToken(UUID value) {
    public RegistrationToken {
        Objects.requireNonNull(value, "value");
    }

    /** Выдаёт новый токен; повторная регистрация всегда должна получить другое значение. */
    static RegistrationToken create() {
        return new RegistrationToken(UUID.randomUUID());
    }
}
