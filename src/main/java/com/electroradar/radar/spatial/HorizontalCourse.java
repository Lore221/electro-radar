package com.electroradar.radar.spatial;

import net.minecraft.world.phys.Vec3;

/** Remembers the last useful horizontal heading through near-vertical poses. */
public final class HorizontalCourse {
    private Vec3 previous = new Vec3(0, 0, -1);
    public Vec3 resolve(Vec3 direction) {
        double length = direction.horizontalDistanceSqr();
        if (length > 1.0E-6D * direction.lengthSqr()) {
            previous = new Vec3(direction.x, 0, direction.z).scale(1.0D / Math.sqrt(length));
        }
        return previous;
    }
}
