package com.electroradar.radar.monitor;

import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.RadarRefreshSchedule;

/**
 * Частота обновления снимка монитора.
 *
 * <p>Обычные снимки следуют той же {@value #INTERVAL_TICKS}-тиковой фазе, в которой радары
 * сети измеряют известные цели. Фаза не зависит от времени загрузки монитора или адресных
 * запросов. Срочные изменения (сеть, сборка, выбор цели, открытие GUI) применяются сразу.</p>
 */
final class MonitorRefreshGate {
    /** 5 обновлений в секунду. */
    static final long INTERVAL_TICKS = RadarRefreshSchedule.INTERVAL_TICKS;

    private long lastRefreshTick;
    private boolean refreshed;

    /**
     * Ограничивает только дорогую пересборку. Доставка готового снимка выполняется при
     * каждом вызове: адресный запрос не должен откладывать рассылку другим наблюдателям.
     */
    void refreshAndPublish(long now, boolean urgent, NetworkId networkId, Runnable refresh, Runnable publish) {
        if (due(now, urgent, networkId)) {
            refreshed(now);
            refresh.run();
        }
        publish.run();
    }

    /**
     * @param now тик менеджера сети, совпадающий с часами радарных измерений
     * @param urgent изменение, которое нельзя откладывать
     */
    boolean due(long now, boolean urgent, NetworkId networkId) {
        return urgent || !refreshed || now < lastRefreshTick
                || now != lastRefreshTick && RadarRefreshSchedule.isDue(now, networkId);
    }

    void refreshed(long now) {
        lastRefreshTick = now;
        refreshed = true;
    }

    void reset() {
        refreshed = false;
    }
}
