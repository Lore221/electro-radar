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

    /** Сравнение метаданных не должно запускать построение изображения. */
    public boolean sameVisibleState(StructureMeasurement other) {
        return other != null && name.equals(other.name) && aimCenter.equals(other.aimCenter)
                && velocity.equals(other.velocity) && Double.compare(aimRadius, other.aimRadius) == 0
                && scanAnchor.equals(other.scanAnchor) && silhouette.sameView(other.silhouette);
    }

    public StructureMeasurement withScanAnchor(ScanAnchor anchor) {
        return new StructureMeasurement(name, silhouette, aimCenter, velocity, aimRadius, Optional.of(anchor));
    }
}
