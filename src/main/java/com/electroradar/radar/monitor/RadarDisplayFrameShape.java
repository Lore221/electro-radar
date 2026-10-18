package com.electroradar.radar.monitor;

import net.minecraft.util.StringRepresentable;

/**
 * Набор видимых краёв рамки одного рабочего прямоугольника.
 *
 * <p>Названия совпадают с моделями Power-radar, чтобы сохранить готовую геометрию панелей.
 * Одинаковая форма используется моделью блока и Create Connected Textures.</p>
 */
public enum RadarDisplayFrameShape implements StringRepresentable {
    SINGLE("single"), CENTER("center"), TOP("top"), BOTTOM("bottom"), LEFT("left"), RIGHT("right"),
    TOP_LEFT("top_left"), TOP_RIGHT("top_right"), BOTTOM_LEFT("bottom_left"), BOTTOM_RIGHT("bottom_right"),
    VERTICAL("vertical"), VERTICAL_TOP("vertical_top"), VERTICAL_BOTTOM("vertical_bottom"),
    HORIZONTAL("horizontal"), HORIZONTAL_LEFT("horizontal_left"), HORIZONTAL_RIGHT("horizontal_right");

    private final String serializedName;

    RadarDisplayFrameShape(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    public boolean hasTopEdge() {
        return switch (this) {
            case SINGLE, TOP, TOP_LEFT, TOP_RIGHT, VERTICAL_TOP, HORIZONTAL, HORIZONTAL_LEFT, HORIZONTAL_RIGHT -> true;
            default -> false;
        };
    }

    public boolean hasBottomEdge() {
        return switch (this) {
            case SINGLE, BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT, VERTICAL_BOTTOM, HORIZONTAL, HORIZONTAL_LEFT,
                    HORIZONTAL_RIGHT -> true;
            default -> false;
        };
    }

    public boolean hasLeftEdge() {
        return switch (this) {
            case SINGLE, LEFT, TOP_LEFT, BOTTOM_LEFT, VERTICAL, VERTICAL_TOP, VERTICAL_BOTTOM, HORIZONTAL_LEFT -> true;
            default -> false;
        };
    }

    public boolean hasRightEdge() {
        return switch (this) {
            case SINGLE, RIGHT, TOP_RIGHT, BOTTOM_RIGHT, VERTICAL, VERTICAL_TOP, VERTICAL_BOTTOM,
                    HORIZONTAL_RIGHT -> true;
            default -> false;
        };
    }
}
