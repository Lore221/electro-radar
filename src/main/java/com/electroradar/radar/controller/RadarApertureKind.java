package com.electroradar.radar.controller;

/** Геометрическая форма одной однородной апертуры контроллера. */
public enum RadarApertureKind {
    PANEL("panel"),
    OVERVIEW("overview");

    private final String serializedName;

    RadarApertureKind(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** Старые контрапции Electro Radar и неизвестные теги сохраняют прежний тип панели. */
    public static RadarApertureKind fromSavedName(String name) {
        for (RadarApertureKind kind : values()) {
            if (kind.serializedName.equals(name)) return kind;
        }
        return PANEL;
    }
}
