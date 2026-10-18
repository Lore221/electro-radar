package com.electroradar.radar.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.scan.AabbRadarScanArea;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Единое описание демонстрационной области debug_radar для серверного поиска и клиентской карты.
 *
 * <p>Размеры здесь намеренно общие: изменение области поиска автоматически меняет прямоугольник,
 * который монитор передаёт для рисования. Профиль квадратный только в плоскости X/Z; высота AABB
 * остаётся частью реального сканирования и не влияет на карту.</p>
 */
public final class DebugRadarVisualArea {
    public static final double SIZE_X = 100.0D;
    public static final double SIZE_Y = 30.0D;
    public static final double SIZE_Z = 100.0D;

    private DebugRadarVisualArea() {
    }

    /** Создаёт прежнюю полную AABB-область одним проходом радара. */
    public static AabbRadarScanArea scanArea(BlockPos position) {
        return AabbRadarScanArea.centered(Vec3.atCenterOf(position), SIZE_X, SIZE_Y, SIZE_Z);
    }

    /** Создаёт её плоское прямоугольное представление с тем же центром и полуразмерами. */
    public static RadarVisualProfile profile(net.minecraft.resources.ResourceLocation dimension,
                                             BlockPos position) {
        Vec3 center = Vec3.atCenterOf(position);
        return RadarVisualProfile.rectangle(dimension, position, center.x, center.y, center.z,
                SIZE_X * 0.5D, SIZE_Z * 0.5D, RadarVisualProfile.DEFAULT_SECTOR_COUNT);
    }
}
