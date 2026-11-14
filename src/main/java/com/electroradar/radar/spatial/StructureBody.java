package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.scan.RadarScanArea;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Phase-local physical geometry with a separately measured, schematic top view. */
public record StructureBody(TargetRef target, List<Part> parts, Vec3 marker, StructureMeasurement measurement) {
    public StructureBody { parts = List.copyOf(parts); }
    public static final class Part {
        private final StructureShape shape;
        private final SpatialTransform transform;
        public Part(List<AABB> boxes, SpatialTransform transform) { this(new StructureShape(boxes), transform); }
        public Part(StructureShape shape, SpatialTransform transform) {
            this.shape = Objects.requireNonNull(shape);
            this.transform = Objects.requireNonNull(transform);
        }
        public StructureShape shape() { return shape; }
        public List<AABB> boxes() { return shape.boxes(); }
        public SpatialTransform transform() { return transform; }
        @Override public boolean equals(Object other) {
            return other instanceof Part part && transform.equals(part.transform)
                    && (shape == part.shape || boxes().equals(part.boxes()));
        }
        @Override public int hashCode() { return 31 * boxes().hashCode() + transform.hashCode(); }
    }
    public boolean intersects(RadarScanArea area) {
        for (Part part : parts) if (part.shape.intersects(area, part.transform)) return true;
        return false;
    }

    public Optional<Vec3> scanPoint(RadarScanArea area) {
        for (Part part : parts) {
            Optional<Vec3> point = part.shape.scanPoint(area, part.transform);
            if (point.isPresent()) return point;
        }
        return Optional.empty();
    }
}
