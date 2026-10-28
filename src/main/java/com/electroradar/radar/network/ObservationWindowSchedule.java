package com.electroradar.radar.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.electroradar.radar.visual.RadarSectorOrder;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Источнико-независимое описание того, какое окно действует в серверный тик.
 * Обычный семисекторный радар возвращает окна своей сети; иной источник может предоставить
 * собственное расписание через тот же контракт. Метод вызывается только в ServerTickEvent.Pre.
 */
@FunctionalInterface
public interface ObservationWindowSchedule {
    Optional<ObservationWindow> windowAt(long serverTick, NetworkWindow networkWindow);

    /** Число базовых окон в группе; длительность сети меняется только на границе всей группы. */
    default int networkWindowMultiple() { return 1; }

    /** Объединяет соседние окна сети, сохраняя общие границы и порядок шагов источника. */
    static ObservationWindowSchedule everyNetworkWindows(int windows, ObservationWindowSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        int sourceMultiple = schedule.networkWindowMultiple();
        if (windows <= 0 || sourceMultiple <= 0) {
            throw new IllegalArgumentException("Network window multiple must be positive");
        }
        int multiple = Math.multiplyExact(windows, sourceMultiple);
        return new ObservationWindowSchedule() {
            @Override
            public int networkWindowMultiple() { return multiple; }

            @Override
            public Optional<ObservationWindow> windowAt(long serverTick, NetworkWindow window) {
                long duration = window.durationTicks();
                long start = Math.subtractExact(window.startTick(),
                        Math.multiplyExact(window.slotSerial() % windows, duration));
                // Новый источник может присоединиться посреди группы после смены D.
                // Если её расчётное начало раньше нуля, ждём следующую полную группу.
                if (start < 0L) return Optional.empty();
                long end = Math.addExact(start, Math.multiplyExact(duration, windows));
                return schedule.windowAt(serverTick, new NetworkWindow(window.slotSerial() / windows, start, end));
            }
        };
    }

    /**
     * Текущее окно одной радарной сети. Источники могут объединять целое число её окон;
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
            if (activeSectorIndices.isEmpty() || activeSectorIndices.size() > RadarVisualProfile.MAX_SECTOR_COUNT
                    || startTick < 0 || endTick <= startTick || fadeDurationTicks < 0
                    || new HashSet<>(activeSectorIndices).size() != activeSectorIndices.size()
                    || activeSectorIndices.stream().anyMatch(index -> index < 0
                            || index >= RadarVisualProfile.MAX_SECTOR_COUNT)) {
                throw new IllegalArgumentException("Invalid source observation window");
            }
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
