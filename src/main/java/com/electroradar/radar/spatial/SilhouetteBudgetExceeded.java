package com.electroradar.radar.spatial;

/** Ограничение только экранной сетки; физическая форма остаётся доступной. */
final class SilhouetteBudgetExceeded extends IllegalStateException {
    SilhouetteBudgetExceeded(String message) { super(message); }
}
