package com.electroradar.cannon.controller;

/** Первый статус отправляется сразу; последующие изменения — не чаще раза в 10 тиков. */
final class StatusSync {
    private static final long INTERVAL_TICKS = 10;
    private FireStatus previous;
    private long sentAt;

    boolean update(FireStatus status, long now) {
        if (status == previous) return false;
        if (previous != null && now >= sentAt && now - sentAt < INTERVAL_TICKS) return false;
        previous = status;
        sentAt = now;
        return true;
    }
}
