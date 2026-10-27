package com.electroradar.radar.network;

import java.util.Objects;
import java.util.Optional;

import com.electroradar.radar.visual.RadarSectorOrder;

/**
 * Источнико-независимое описание того, какое окно действует в серверный тик.
 * Обычный семисекторный радар возвращает окна общей фазы; иной источник может предоставить
 * собственное расписание через тот же контракт. Метод вызывается только в ServerTickEvent.Pre.
 */
@FunctionalInterface
public interface ObservationWindowSchedule {
    Optional<ObservationWindow> windowAt(long serverTick, GlobalWindow globalWindow);

    /** Общая граница текущего секторного радара, доступная всем зарегистрированным источникам. */
    record GlobalWindow(long slotSerial, long startTick, long endTick) {
        public GlobalWindow {
            if (slotSerial < 0 || startTick < 0 || endTick <= startTick) {
                throw new IllegalArgumentException("Invalid global observation window");
            }
        }

        public long durationTicks() { return endTick - startTick; }
    }

    /** Окно одного источника; пустой Optional означает штатную паузу без живых прав. */
    record ObservationWindow(long slotSerial, int activeSectorIndex, long startTick, long endTick,
                            long fadeDurationTicks) {
        public ObservationWindow {
            if (slotSerial < 0 || activeSectorIndex < 0 || startTick < 0 || endTick <= startTick
                    || fadeDurationTicks < 0) {
                throw new IllegalArgumentException("Invalid source observation window");
            }
        }
    }

    /**
     * Политика нынешних радаров: каждый глобальный слот посещает следующий индекс перестановки,
     * а память длится один полный цикл источника. Перестановка строится один раз на плане.
     */
    static ObservationWindowSchedule globalSectorCycle(int sectorCount, int orderVariant) {
        int[] order = RadarSectorOrder.permutation(sectorCount, orderVariant);
        return (serverTick, global) -> {
            Objects.requireNonNull(global, "globalWindow");
            int index = order[(int) Math.floorMod(global.slotSerial(), (long) order.length)];
            long fadeTicks = Math.multiplyExact(global.durationTicks(), order.length);
            return Optional.of(new ObservationWindow(global.slotSerial(), index,
                    global.startTick(), global.endTick(), fadeTicks));
        };
    }
}
