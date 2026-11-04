package com.electroradar.cannon.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutocannonFireTest {
    @Test void enablesTheNativeRateOnlyDuringTheCall() {
        var rate = new AtomicInteger(0);
        assertTrue(AutocannonFire.withRate(rate.get(), rate::set, () -> {
            assertEquals(15, rate.get());
            return true;
        }));
        assertEquals(0, rate.get(), "не оставлять автопушку вооружённой после вызова");
    }

    @Test void restoresAnExternalSettingAfterRefusalOrException() {
        var rate = new AtomicInteger(7);
        assertFalse(AutocannonFire.withRate(rate.get(), rate::set, () -> false));
        assertEquals(7, rate.get());
        assertThrows(IllegalStateException.class, () -> AutocannonFire.withRate(rate.get(), rate::set, () -> {
            throw new IllegalStateException("failed attempt");
        }));
        assertEquals(7, rate.get());
    }
}
