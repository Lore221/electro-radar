package com.electroradar.radar.scan;

import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Простейшая область сканирования: одна и та же AABB используется для грубого запроса и точной проверки.
 * Попадание определяется пересечением AABB области с bounding box сущности, а не положением её центра.
 */
public record AabbRadarScanArea(AABB bounds) implements RadarScanArea {
    public AabbRadarScanArea {
        Objects.requireNonNull(bounds, "bounds");
    }

    /**
     * Создаёт AABB заданного размера вокруг мирового центра.
     * Размеры задаются отдельно по X, Y и Z; нулевые, отрицательные и бесконечные значения
     * отклоняются, чтобы ошибка конфигурации не превратилась в молчаливый пустой поиск.
     */
    public static AabbRadarScanArea centered(Vec3 center, double sizeX, double sizeY, double sizeZ) {
        Objects.requireNonNull(center, "center");
        if (!Double.isFinite(sizeX) || !Double.isFinite(sizeY) || !Double.isFinite(sizeZ)
                || sizeX <= 0.0 || sizeY <= 0.0 || sizeZ <= 0.0) {
            throw new IllegalArgumentException("AABB scan dimensions must be finite and positive");
        }
        double halfX = sizeX / 2.0;
        double halfY = sizeY / 2.0;
        double halfZ = sizeZ / 2.0;
        return new AabbRadarScanArea(new AABB(
                center.x - halfX, center.y - halfY, center.z - halfZ,
                center.x + halfX, center.y + halfY, center.z + halfZ));
    }

    @Override
    public AABB queryBounds() {
        return bounds;
    }

    @Override
    public boolean contains(Entity entity) {
        return bounds.intersects(entity.getBoundingBox());
    }
}
