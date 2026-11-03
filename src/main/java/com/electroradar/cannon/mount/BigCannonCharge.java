package com.electroradar.cannon.mount;

/** Порядок CBC fireShot: минимум материала применяется до встроенного заряда. */
final class BigCannonCharge {
    private BigCannonCharge() {}

    static float launchSpeed(float charges, float minimumSpread, float integratedCharge) {
        return Math.max(charges, minimumSpread) + Math.max(0.0F, integratedCharge);
    }
}
