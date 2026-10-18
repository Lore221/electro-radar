package com.electroradar.radar.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Чистая X/Z-проекция для GUI и поверхности в мире.
 *
 * <p>Ось Y не меняет место метки. Аргумент mapDiameter задаёт видимый диаметр в блоках;
 * локальные смещения принадлежат только клиентскому виду и не меняют серверный список целей.</p>
 */
public final class RadarDisplayProjector {
    private RadarDisplayProjector() {
    }

    /** Возвращает нормализованную позицию в диапазоне около [-1; 1]; обрезку делает вызывающий слой. */
    public static MapPoint project(ResourceLocation targetDimension, ResourceLocation mapDimension,
                                   BlockPos center, Direction facing, double x, double z,
                                   double centerOffsetX, double centerOffsetZ, double mapDiameter) {
        return project(targetDimension, mapDimension,
                center.getX() + 0.5D, center.getZ() + 0.5D, facing,
                x, z, centerOffsetX, centerOffsetZ, mapDiameter);
    }

    /**
     * Вариант проекции для геометрического центра составного экрана, который может лежать
     * между блоками. Смещения GUI остаются клиентскими и добавляются после центра сборки.
     */
    public static MapPoint project(ResourceLocation targetDimension, ResourceLocation mapDimension,
                                   double centerX, double centerZ, Direction facing,
                                   double x, double z, double centerOffsetX, double centerOffsetZ,
                                   double mapDiameter) {
        if (!targetDimension.equals(mapDimension)) return null;
        double safeDiameter = Math.max(1.0D, mapDiameter);
        double dx = x - (centerX + centerOffsetX);
        double dz = z - (centerZ + centerOffsetZ);
        Direction right = RadarDisplayStructureResolver.right(facing);
        double horizontal = dx * right.getStepX() + dz * right.getStepZ();
        double forward = dx * facing.getStepX() + dz * facing.getStepZ();
        return new MapPoint(horizontal / (safeDiameter * 0.5D),
                forward / (safeDiameter * 0.5D));
    }

    /** Нормализованные координаты: +X вправо, север (+Z отрицательное) вверх. */
    public record MapPoint(double x, double y) {
        public boolean insideSquare() {
            return Math.abs(x) <= 1.0D && Math.abs(y) <= 1.0D;
        }
    }
}
