package com.electroradar.radar.monitor;

/**
 * Частота обновления снимка монитора.
 *
 * <p>Сеть меняет версию содержимого почти каждый тик, пока цели движутся: каждое новое
 * положение — видимое изменение. Пересобирать и рассылать полный снимок каждого монитора на
 * каждый тик — основная нагрузка мониторов на сервер, сеть и клиент. Позиции на радарной
 * карте достаточно обновлять {@value #INTERVAL_TICKS}-тиковыми шагами; срочные изменения
 * (сеть, сборка, выбор цели, открытие GUI) применяются сразу.</p>
 */
final class MonitorRefreshGate {
    /** 5 обновлений в секунду. */
    static final long INTERVAL_TICKS = 4;

    private long lastRefreshTick;
    private boolean refreshed;

    /**
     * Ограничивает только дорогую пересборку. Доставка готового снимка выполняется при
     * каждом вызове: адресный запрос не должен откладывать рассылку другим наблюдателям.
     */
    void refreshAndPublish(long now, boolean urgent, Runnable refresh, Runnable publish) {
        if (due(now, urgent)) {
            refreshed(now);
            refresh.run();
        }
        publish.run();
    }

    /**
     * @param now игровой тик измерения
     * @param urgent изменение, которое нельзя откладывать
     */
    boolean due(long now, boolean urgent) {
        return urgent || !refreshed || now < lastRefreshTick || now - lastRefreshTick >= INTERVAL_TICKS;
    }

    void refreshed(long now) {
        lastRefreshTick = now;
        refreshed = true;
    }

    void reset() {
        refreshed = false;
    }
}
