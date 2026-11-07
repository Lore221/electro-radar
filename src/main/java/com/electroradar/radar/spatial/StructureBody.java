package com.electroradar.radar.spatial;

import java.util.List;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.scan.RadarScanArea;
import com.electroradar.radar.scan.ConvexRadarIntersection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Phase-local physical geometry with a separately measured, schematic top view. */
public record StructureBody(TargetRef target, List<Part> parts, Vec3 marker, StructureMeasurement measurement) {
    public StructureBody { parts = List.copyOf(parts); }
    public record Part(List<AABB> boxes, SpatialTransform transform) {
        public Part { boxes = List.copyOf(boxes); }
    }
    public boolean intersects(RadarScanArea area) {
        for (Part part : parts) for (AABB box : part.boxes()) {
            if (area.queryBounds().inflate(1.0E-7D).intersects(part.transform().bounds(box))
                    && ConvexRadarIntersection.intersects(area, part.transform().corners(box))) return true;
        }
        return false;
    }
}
