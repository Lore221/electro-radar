package com.electroradar.radar.spatial;

import net.minecraft.world.phys.Vec3;
import java.util.Objects;
import java.util.Optional;

/** A structure has its own description, never a fictitious Minecraft EntityType. */
public record StructureMeasurement(String name, Silhouette silhouette, Vec3 aimCenter, Vec3 velocity,
                                   double aimRadius, Optional<ScanAnchor> scanAnchor) {
    public StructureMeasurement {
        Objects.requireNonNull(name); Objects.requireNonNull(silhouette); Objects.requireNonNull(aimCenter);
        Objects.requireNonNull(velocity);
        Objects.requireNonNull(scanAnchor);
        if (!Double.isFinite(aimRadius) || aimRadius < 0) throw new IllegalArgumentException("Structure aim radius");
    }

    public StructureMeasurement(String name, Silhouette silhouette, Vec3 aimCenter, Vec3 velocity, double aimRadius) {
        this(name, silhouette, aimCenter, velocity, aimRadius, Optional.empty());
    }

    public StructureMeasurement withScanAnchor(ScanAnchor anchor) {
        return new StructureMeasurement(name, silhouette, aimCenter, velocity, aimRadius, Optional.of(anchor));
    }
}
