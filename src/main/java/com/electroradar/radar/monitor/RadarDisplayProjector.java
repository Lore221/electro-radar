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
                                   BlockPos center, Direction mapUp, double x, double z,
                                   double centerOffsetX, double centerOffsetZ, double mapDiameter) {
        return project(targetDimension, mapDimension,
                center.getX() + 0.5D, center.getZ() + 0.5D, mapUp,
                x, z, centerOffsetX, centerOffsetZ, mapDiameter);
    }

    /**
     * Вариант проекции для геометрического центра составного экрана, который может лежать
     * между блоками. Смещения GUI остаются клиентскими и добавляются после центра сборки.
     * mapUp задаёт верх карты, а не лицевую сторону физической панели.
     */
    public static MapPoint project(ResourceLocation targetDimension, ResourceLocation mapDimension,
                                   double centerX, double centerZ, Direction mapUp,
                                   double x, double z, double centerOffsetX, double centerOffsetZ,
                                   double mapDiameter) {
        return project(targetDimension, mapDimension, centerX, centerZ, MapFacing.of(mapUp),
                x, z, centerOffsetX, centerOffsetZ, mapDiameter);
    }

    /** Непрерывный верх карты: монитор на движущейся структуре поворачивается вместе с ней. */
    public static MapPoint project(ResourceLocation targetDimension, ResourceLocation mapDimension,
            double centerX, double centerZ, MapFacing mapUp, double x, double z,
            double centerOffsetX, double centerOffsetZ, double mapDiameter) {
        if (!targetDimension.equals(mapDimension)) return null;
        double safeDiameter = Math.max(1.0D, mapDiameter);
        double dx = x - (centerX + centerOffsetX);
        double dz = z - (centerZ + centerOffsetZ);
        MapFacing right = mapUp.right();
        double horizontal = dx * right.getStepX() + dz * right.getStepZ();
        double forward = dx * mapUp.getStepX() + dz * mapUp.getStepZ();
        return new MapPoint(horizontal / (safeDiameter * 0.5D),
                forward / (safeDiameter * 0.5D));
    }

    /** Нормализованные координаты: +X вправо, +Y к выбранному верху карты. */
    public record MapPoint(double x, double y) {
        public boolean insideSquare() {
            return Math.abs(x) <= 1.0D && Math.abs(y) <= 1.0D;
        }
    }
}
