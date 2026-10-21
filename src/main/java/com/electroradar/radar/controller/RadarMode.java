package com.electroradar.radar.controller;

import net.minecraft.core.Direction;

import com.electroradar.config.RadarServerConfig;

/** Неизменяемый смысл типа блока; серверный профиль не размножает BlockEntity-классы. */
public enum RadarMode {
    GROUND("ground", 0.0D),
    AIR("air", 15.0D),
    SURFACE("surface", -15.0D);

    private final String serializedName;
    private final double panelTiltDegrees;

    RadarMode(String serializedName, double panelTiltDegrees) {
        this.serializedName = serializedName;
        this.panelTiltDegrees = panelTiltDegrees;
    }

    /**
     * Возвращает знак угла Create для заданного направления контроллера.
     * Create вращает вокруг положительной мировой оси; для SOUTH/WEST этот же знак опускает
     * переднюю кромку, поэтому семантический наклон «вверх» меняет знак вместе с FACING.
     * Этот угол влияет только на contraption и не входит в область поиска.
     */
    public double panelTiltDegrees(Direction facing) {
        int axisSign = switch (facing) {
            case NORTH, EAST -> 1;
            case SOUTH, WEST -> -1;
            default -> throw new IllegalArgumentException("Radar controller facing must be horizontal");
        };
        return panelTiltDegrees * axisSign;
    }

    public RadarServerConfig.ModeParameters parameters(RadarServerConfig.Snapshot snapshot) {
        return switch (this) {
            case GROUND -> snapshot.ground();
            case AIR -> snapshot.air();
            case SURFACE -> snapshot.surface();
        };
    }

    @Override
    public String toString() {
        return serializedName;
    }
}
