package com.electroradar.radar.spatial;

import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import net.minecraft.world.phys.Vec3;

/** Размещение плоского изображения по горизонтальному курсу, без крена и тангажа. */
record SilhouettePlacement(Vec3 origin, Vec3 forward, double scaleX, double scaleZ) {
    static SilhouettePlacement of(SpatialTransform root, Vec3 forward) {
        return new SilhouettePlacement(root.worldOrigin(), forward, root.x().length(), root.z().length());
    }

    Point project(Point local) {
        return new Point(origin.x-local.x()*scaleX*forward.z-local.z()*scaleZ*forward.x,
                origin.z+local.x()*scaleX*forward.x-local.z()*scaleZ*forward.z);
    }
}
