package com.electroradar.client.monitor;

import com.electroradar.radar.monitor.MapFacing;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;

/**
 * Общая геометрия мировой сетки монитора для GUI и экранной поверхности блока.
 *
 * <p>Сетка привязана к кратным значениям мировых X/Z, а не к пикселям окна. Поэтому при
 * перемещении карты линии проходят под теми же метками, а при смене ориентации карты
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
    static List<MonitorSectorProjection.ProjectedSegment> project(double centerX, double centerZ,
            double offsetX, double offsetZ, MapFacing mapUp, double diameter) {
        double radius = Math.max(0.5D, diameter * 0.5D);
        double reach = radius * Math.sqrt(2.0D);
        double step = stepBlocks(diameter);
        var right = mapUp.right();
        double cx = centerX + offsetX, cz = centerZ + offsetZ;
        ArrayList<MonitorSectorProjection.ProjectedSegment> result = new ArrayList<>();
        for (int axis = 0; axis < 2; axis++) {
            double center = axis == 0 ? cx : cz;
            double first = Math.ceil((center - reach) / step) * step;
            for (int i = 0; i <= MAX_INTERVALS_PER_AXIS * 2; i++) {
                double value = first + i * step;
                if (value > center + reach) break;
                double ax = axis == 0 ? value - cx : -reach;
                double az = axis == 0 ? -reach : value - cz;
                double bx = axis == 0 ? value - cx : reach;
                double bz = axis == 0 ? reach : value - cz;
                var a = new MonitorSectorProjection.MapPoint((ax * right.getStepX() + az * right.getStepZ()) / radius,
                        (ax * mapUp.getStepX() + az * mapUp.getStepZ()) / radius);
                var b = new MonitorSectorProjection.MapPoint((bx * right.getStepX() + bz * right.getStepZ()) / radius,
                        (bx * mapUp.getStepX() + bz * mapUp.getStepZ()) / radius);
                MonitorSectorProjection.clipSegment(a, b).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }
}
