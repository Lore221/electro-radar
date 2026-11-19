package com.electroradar.radar.scan;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Точный круг XZ и включительные границы высоты, без зависимости от поворота антенны. */
public final class CylinderRadarScanArea implements RadarScanArea {
    private final Vec3 center;
    private final double radius;
    private final AABB bounds;

    public CylinderRadarScanArea(Vec3 center,double radius,double minY,double maxY) {
        if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || !Double.isFinite(radius) || radius <= 0 || !Double.isFinite(minY) || !Double.isFinite(maxY) || minY > maxY)
            throw new IllegalArgumentException("Invalid cylindrical radar area");
        this.center=center; this.radius=radius;
        bounds=new AABB(center.x-radius,center.y+minY,center.z-radius,
                center.x+radius,center.y+maxY,center.z+radius);
    }
    @Override public AABB queryBounds() { return bounds; }
    @Override public boolean contains(Entity entity) { return intersectsBounds(entity.getBoundingBox()); }
    @Override public boolean intersectsBounds(AABB box) {
        if (box.maxY < bounds.minY || box.minY > bounds.maxY) return false;
        double x=Math.clamp(center.x,box.minX,box.maxX)-center.x;
        double z=Math.clamp(center.z,box.minZ,box.maxZ)-center.z;
        return x*x+z*z <= radius*radius;
    }
}
