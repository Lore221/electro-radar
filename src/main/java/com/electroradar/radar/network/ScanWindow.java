package com.electroradar.radar.network;

/** Неизменяемое расписание окна, которое доступно зарегистрированному радару. */
public record ScanWindow(long epoch, long boundaryTick, long periodTicks, boolean eligibleThisWindow) {
    public ScanWindow {
        if (epoch <= 0 || boundaryTick < 0 || periodTicks <= 0) {
            throw new IllegalArgumentException("Scan window values are invalid");
        }
    }
}
