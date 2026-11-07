package com.electroradar.radar.monitor;

import net.minecraft.core.Direction;

/**
 * Непрерывное горизонтальное направление, отдельное от физической поверхности блока. Как верх
 * карты: правая ось — по часовой стрелке от него, как {@link Direction#getClockWise()}.
 */
public record MapFacing(double getStepX, double getStepZ) {
    public static MapFacing of(Direction direction) { return new MapFacing(direction.getStepX(), direction.getStepZ()); }
    public MapFacing right() { return new MapFacing(-getStepZ, getStepX); }
    public MapFacing opposite() { return new MapFacing(-getStepX, -getStepZ); }
}
