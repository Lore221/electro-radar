package com.electroradar.cannon.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BigCannonChargeTest {
    @Test
    void integratedRoundAddsItsPowerAfterTheMaterialMinimum() {
        // CBC fireShot raises 4 to 5 before addIntegratedPropellant adds 2.
        // The previous adapter incorrectly combined 4+2 first and returned 6.
        assertEquals(7.0F, BigCannonCharge.launchSpeed(4.0F, 5.0F, 2.0F));
        assertEquals(6.0F, BigCannonCharge.launchSpeed(4.0F, 0.0F, 2.0F));
        assertEquals(5.0F, BigCannonCharge.launchSpeed(4.0F, 5.0F, 0.0F));
        assertEquals(4.0F, BigCannonCharge.launchSpeed(4.0F, 0.0F, -2.0F));
    }
}
