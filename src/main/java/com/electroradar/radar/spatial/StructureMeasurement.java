package com.electroradar.radar.spatial;

import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/** A structure has its own description, never a fictitious Minecraft EntityType. */
public record StructureMeasurement(String name, Silhouette silhouette, Vec3 aimCenter, Vec3 velocity,
                                   double aimRadius) {
    public StructureMeasurement {
        Objects.requireNonNull(name); Objects.requireNonNull(silhouette); Objects.requireNonNull(aimCenter);
        Objects.requireNonNull(velocity);
        if (!Double.isFinite(aimRadius) || aimRadius < 0) throw new IllegalArgumentException("Structure aim radius");
    }
}
