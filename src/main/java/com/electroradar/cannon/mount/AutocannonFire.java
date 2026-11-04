package com.electroradar.cannon.mount;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Прежний сигнал 15 задавал максимальный штатный темп. Теперь он действует только внутри вызова. */
final class AutocannonFire {
    private AutocannonFire() {}

    static boolean withRate(int previous, IntConsumer setRate, BooleanSupplier action) {
        setRate.accept(15);
        try {
            return action.getAsBoolean();
        } finally {
            setRate.accept(previous);
        }
    }
}
