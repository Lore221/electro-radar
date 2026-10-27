package com.electroradar.radar.visual;

import java.util.Arrays;
import java.util.Random;

/**
 * Четыре воспроизводимых перестановки для каждого допустимого числа частей.
 * Таблица не зависит от мира, клиента и эпохи; наружу возвращаются копии массивов.
 * Это порядок анимации уже опубликованных данных, а не расписание серверного сканера.
 */
public final class RadarSectorOrder {
    public static final int VARIANT_COUNT = 4;
    private static final long SEED = 0x5ECA7A1D93B46F02L;
    private static final int[][][] ORDERS = buildOrders();

    private RadarSectorOrder() {
    }

    public static boolean validVariant(int variant) {
        return variant >= 1 && variant <= VARIANT_COUNT;
    }

    public static int[] permutation(int partCount, int variant) {
        if (partCount < RadarVisualProfile.MIN_SECTOR_COUNT || partCount > RadarVisualProfile.MAX_SECTOR_COUNT
                || !validVariant(variant)) {
            throw new IllegalArgumentException("Invalid radar sector order");
        }
        return ORDERS[partCount][variant - 1].clone();
    }

    private static int[][][] buildOrders() {
        int[][][] table = new int[RadarVisualProfile.MAX_SECTOR_COUNT + 1][][];
        for (int count = RadarVisualProfile.MIN_SECTOR_COUNT; count < table.length; count++) {
            table[count] = buildVariants(count);
        }
        return table;
    }

    private static int[][] buildVariants(int count) {
        int[][] variants = new int[VARIANT_COUNT][];
        // У одной части есть одна перестановка, у двух — две; при трёх уже доступны четыре.
        int distinct = count == 1 ? 1 : count == 2 ? 2 : VARIANT_COUNT;
        for (int variant = 0; variant < VARIANT_COUNT; variant++) {
            if (variant >= distinct) {
                variants[variant] = variants[variant % distinct].clone();
                continue;
            }
            int[] order = new int[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Random random = new Random(SEED ^ ((long) count << 32)
                    ^ (variant + 1L) * 0x9E3779B97F4A7C15L);
            for (int i = count - 1; i > 0; i--) {
                swap(order, i, random.nextInt(i + 1));
            }
            // Совпадение устраняется за конечное число шагов без случайного поиска.
            while (alreadyUsed(variants, variant, order) || count >= 4 && circularSweep(order)) {
                nextPermutation(order);
            }
            variants[variant] = order;
        }
        return variants;
    }

    private static boolean alreadyUsed(int[][] variants, int count, int[] candidate) {
        for (int i = 0; i < count; i++) {
            if (Arrays.equals(variants[i], candidate)) return true;
        }
        return false;
    }

    private static boolean circularSweep(int[] order) {
        int step = Math.floorMod(order[1] - order[0], order.length);
        if (step != 1 && step != order.length - 1) return false;
        for (int i = 2; i < order.length; i++) {
            if (Math.floorMod(order[i] - order[i - 1], order.length) != step) return false;
        }
        return true;
    }

    /** Следующий лексикографический порядок с переходом от последнего к первому. */
    private static void nextPermutation(int[] order) {
        int pivot = order.length - 2;
        while (pivot >= 0 && order[pivot] >= order[pivot + 1]) pivot--;
        if (pivot >= 0) {
            int successor = order.length - 1;
            while (order[successor] <= order[pivot]) successor--;
            swap(order, pivot, successor);
        }
        for (int left = pivot + 1, right = order.length - 1; left < right; left++, right--) {
            swap(order, left, right);
        }
    }

    private static void swap(int[] values, int first, int second) {
        int previous = values[first];
        values[first] = values[second];
        values[second] = previous;
    }
}
