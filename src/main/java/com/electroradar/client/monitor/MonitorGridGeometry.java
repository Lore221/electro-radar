package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;

import com.electroradar.radar.monitor.RadarDisplayStructureResolver;

/**
 * Общая геометрия мировой сетки монитора для GUI и экранной поверхности блока.
 *
 * <p>Сетка привязана к кратным значениям мировых X/Z, а не к пикселям окна. Поэтому при
 * перемещении карты линии проходят под теми же метками, а при смене направления панели
 * поворачиваются на четверть оборота вместе с проекцией. Нормированная координата линии
 * измеряется от центра: для вертикали положительное значение направлено вправо, для
 * горизонтали — вниз по экрану.</p>
 */
final class MonitorGridGeometry {
    private static final int NEAR_LIMIT_BLOCKS = 2_000;
    private static final int MID_LIMIT_BLOCKS = 5_000;
    private static final int NEAR_STEP_BLOCKS = 100;
    private static final int MID_STEP_BLOCKS = 500;
    private static final int FAR_STEP_BLOCKS = 1_000;
    private static final int MAX_INTERVALS_PER_AXIS = 100;

    private MonitorGridGeometry() {
    }

    /**
     * Возвращает шаг линий по видимому диаметру карты, как в прежнем мониторе.
     * Диаметр, а не расстояние до края, сравнивается с порогами LOD.
     */
    static double stepBlocks(double mapDiameterBlocks) {
        if (mapDiameterBlocks <= NEAR_LIMIT_BLOCKS) return NEAR_STEP_BLOCKS;
        if (mapDiameterBlocks <= MID_LIMIT_BLOCKS) return MID_STEP_BLOCKS;
        // Удалённый подключённый радар может потребовать карту шире прежних 10 000 блоков.
        // Укрупняем мировую сетку, чтобы её стоимость не росла вместе с расстоянием до радара.
        return FAR_STEP_BLOCKS * Math.max(1.0D,
                Math.ceil(mapDiameterBlocks / (FAR_STEP_BLOCKS * (double) MAX_INTERVALS_PER_AXIS)));
    }

    /**
     * Строит видимые отрезки глобальной квадратной сетки в осях экрана.
     * Смещение центра — клиентское состояние перетаскиваемого GUI; world-renderer передаёт нули.
     */
    static List<GridLine> project(double centerX, double centerZ, double centerOffsetX,
                                  double centerOffsetZ, Direction facing, double mapDiameterBlocks) {
        double radius = Math.max(0.5D, mapDiameterBlocks * 0.5D);
        double step = stepBlocks(mapDiameterBlocks);
        Direction right = RadarDisplayStructureResolver.right(facing);
        double shiftedCenterX = centerX + centerOffsetX;
        double shiftedCenterZ = centerZ + centerOffsetZ;
        ArrayList<GridLine> lines = new ArrayList<>();

        appendWorldAxis(lines, shiftedCenterX, radius, step,
                right.getStepX() != 0, right.getStepX(), facing.getStepX());
        appendWorldAxis(lines, shiftedCenterZ, radius, step,
                right.getStepZ() != 0, right.getStepZ(), facing.getStepZ());
        return List.copyOf(lines);
    }

    /** Добавляет линии одного мирового измерения и переводит его на горизонталь или вертикаль экрана. */
    private static void appendWorldAxis(List<GridLine> lines, double center, double radius, double step,
                                        boolean projectsHorizontally, int rightSign, int facingSign) {
        double firstLine = Math.ceil((center - radius) / step) * step;
        double lastLine = Math.floor((center + radius) / step) * step;
        for (double worldCoordinate = firstLine; worldCoordinate <= lastLine; worldCoordinate += step) {
            double delta = worldCoordinate - center;
            if (projectsHorizontally) {
                lines.add(new GridLine(true, delta * rightSign / radius));
            } else {
                // Экранная Y направлена вниз, тогда как вектор facing задаёт направление к верху карты.
                lines.add(new GridLine(false, -delta * facingSign / radius));
            }
        }
    }

    /** Тип линии и её экранная координата относительно центра карты в диапазоне [-1; 1]. */
    record GridLine(boolean vertical, double normalizedCoordinate) {
    }
}
