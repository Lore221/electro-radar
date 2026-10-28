package com.electroradar.radar.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.electroradar.radar.visual.RadarSectorOrder;

/**
 * Источнико-независимое описание того, какое окно действует в серверный тик.
 * Обычный семисекторный радар возвращает окна своей сети; иной источник может предоставить
 * собственное расписание через тот же контракт. Метод вызывается только в ServerTickEvent.Pre.
 */
@FunctionalInterface
public interface ObservationWindowSchedule {
    Optional<ObservationWindow> windowAt(long serverTick, NetworkWindow networkWindow);

    /**
     * Текущее окно одной радарной сети. Внутри сети все радары меняют сектор синхронно;
     * разные сети начинают окна со сдвигом, поэтому поиск не собирается в один пиковый тик.
     *
     * @param slotSerial номер окна внутри этой сети, начиная с 0
     */
    record NetworkWindow(long slotSerial, long startTick, long endTick) {
        public NetworkWindow {
            if (slotSerial < 0 || startTick < 0 || endTick <= startTick) {
                throw new IllegalArgumentException("Invalid network observation window");
            }
        }

        public long durationTicks() { return endTick - startTick; }
    }

    /** Окно одного источника; пустой Optional означает штатную паузу без живых прав. */
    record ObservationWindow(List<Integer> activeSectorIndices, long startTick,
                             long endTick, long fadeDurationTicks) {
        public ObservationWindow {
            activeSectorIndices = List.copyOf(activeSectorIndices);
            if (activeSectorIndices.isEmpty() || activeSectorIndices.size() > 64
                    || startTick < 0 || endTick <= startTick || fadeDurationTicks < 0
                    || new HashSet<>(activeSectorIndices).size() != activeSectorIndices.size()
                    || activeSectorIndices.stream().anyMatch(index -> index == null || index < 0 || index >= 64)) {
                throw new IllegalArgumentException("Invalid source observation window");
            }
        }

        public ObservationWindow(int activeSectorIndex, long startTick, long endTick,
                                 long fadeDurationTicks) {
            this(List.of(activeSectorIndex), startTick, endTick, fadeDurationTicks);
        }

        /** Singleton compatibility accessor for ordinary one-zone radar schedules. */
        public int activeSectorIndex() {
            if (activeSectorIndices.size() != 1) {
                throw new IllegalStateException("Observation window contains multiple active sectors");
            }
            return activeSectorIndices.getFirst();
        }
    }

    /**
     * Политика нынешних радаров: каждое окно сети посещает следующий индекс перестановки,
     * а память длится один полный цикл источника. Перестановка строится один раз на плане.
     */
    static ObservationWindowSchedule sectorCycle(int sectorCount, int orderVariant) {
        int[] order = RadarSectorOrder.permutation(sectorCount, orderVariant);
        return (serverTick, window) -> {
            Objects.requireNonNull(window, "networkWindow");
            int index = order[(int) Math.floorMod(window.slotSerial(), (long) order.length)];
            long fadeTicks = Math.multiplyExact(window.durationTicks(), order.length);
            return Optional.of(new ObservationWindow(List.of(index), window.startTick(), window.endTick(), fadeTicks));
        };
    }
}
