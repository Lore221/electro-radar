package com.electroradar.radar.spatial;

import com.electroradar.radar.network.EndpointKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** One loaded device placement, with storage coordinates separate from its stable endpoint. */
public record Placement(EndpointKey endpoint, BlockPos storagePosition, SpatialTransform transform) {
    public Placement { storagePosition = storagePosition.immutable(); }
    public Vec3 center() { return transform.toWorld(Vec3.atCenterOf(storagePosition)); }
}
