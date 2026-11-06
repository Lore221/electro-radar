package com.electroradar.radar.monitor;

import net.minecraft.core.Direction;

/** A continuous horizontal map basis, separate from the physical block surface. */
public record MapFacing(double getStepX, double getStepZ) {
    public static MapFacing of(Direction direction) { return new MapFacing(direction.getStepX(), direction.getStepZ()); }
    public MapFacing right() { return new MapFacing(getStepZ, -getStepX); }
}
