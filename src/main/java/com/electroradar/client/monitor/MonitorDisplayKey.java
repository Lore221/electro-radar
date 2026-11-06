package com.electroradar.client.monitor;

import java.util.UUID;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.spatial.RadarSpatial;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Current monitor load, addressed by stable placement rather than world position. */
public record MonitorDisplayKey(EndpointKey endpoint, UUID displayId, UUID loadId) {
    public MonitorDisplayKey(ResourceLocation dimension, BlockPos ignoredStoragePosition,
            UUID displayId, UUID loadId, EndpointKey endpoint) {
        this(endpoint, displayId, loadId);
        if (!dimension.equals(endpoint.dimension().location())) throw new IllegalArgumentException("Monitor dimension mismatch");
    }
    public ResourceLocation dimension() { return endpoint.dimension().location(); }
    public BlockPos rootPosition() {
        return endpoint instanceof EndpointKey.Static fixed ? fixed.position() : ((EndpointKey.Moving)endpoint).localPosition();
    }
    public BlockPos storagePosition(Level level) { return RadarSpatial.resolve(level, endpoint).orElse(null); }
}
