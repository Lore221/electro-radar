package com.electroradar.radar.spatial;

import java.util.Objects;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.scan.RadarScanArea;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Уже поддерживаемые пространством габариты; блоки и экранная форма не запрашиваются. */
public record StructureContact(TargetRef target,AABB bounds,Vec3 velocity) {
    public StructureContact {
        Objects.requireNonNull(target); Objects.requireNonNull(bounds); Objects.requireNonNull(velocity);
    }
    public com.electroradar.radar.spatial.ScanAnchor anchor(com.electroradar.radar.network.EndpointKey source,
                                                           RadarScanArea area) {
        var query=area.queryBounds().getCenter();
        double x=Math.clamp(query.x,bounds.minX,bounds.maxX);
        double z=Math.clamp(query.z,bounds.minZ,bounds.maxZ);
        return new ScanAnchor(source,new com.electroradar.radar.visual.RadarVisualGeometry.Point(x,z));
    }
    public boolean intersects(RadarScanArea area) { return area.intersectsBounds(bounds); }
}
