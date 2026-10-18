package com.electroradar.radar.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Геометрия экранной плоскости и определение видимых сторон панели. */
public final class RadarDisplayStructureResolver {
    public static final int MAX_SIZE = 5;

    private RadarDisplayStructureResolver() {
    }

    /** Направление вправо на фронтальной плоскости, соответствующее старым моделям монитора. */
    public static Direction right(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.EAST;
        };
    }

    /** Возвращает блок по локальным координатам экрана: U — вправо, V — вверх. */
    public static BlockPos at(BlockPos origin, Direction facing, int u, int v) {
        return origin.relative(right(facing), u).above(v);
    }

    /** Определяет рамку панели внутри уже выбранного полного прямоугольника. */
    public static RadarDisplayFrameShape frame(int u, int v, int width, int height) {
        boolean left = u == 0;
        boolean right = u == width - 1;
        boolean bottom = v == 0;
        boolean top = v == height - 1;
        if (width == 1 && height == 1) return RadarDisplayFrameShape.SINGLE;
        if (width == 1) {
            if (bottom) return RadarDisplayFrameShape.VERTICAL_BOTTOM;
            if (top) return RadarDisplayFrameShape.VERTICAL_TOP;
            return RadarDisplayFrameShape.VERTICAL;
        }
        if (height == 1) {
            if (left) return RadarDisplayFrameShape.HORIZONTAL_LEFT;
            if (right) return RadarDisplayFrameShape.HORIZONTAL_RIGHT;
            return RadarDisplayFrameShape.HORIZONTAL;
        }
        if (left && bottom) return RadarDisplayFrameShape.BOTTOM_LEFT;
        if (right && bottom) return RadarDisplayFrameShape.BOTTOM_RIGHT;
        if (left && top) return RadarDisplayFrameShape.TOP_LEFT;
        if (right && top) return RadarDisplayFrameShape.TOP_RIGHT;
        if (left) return RadarDisplayFrameShape.LEFT;
        if (right) return RadarDisplayFrameShape.RIGHT;
        if (bottom) return RadarDisplayFrameShape.BOTTOM;
        if (top) return RadarDisplayFrameShape.TOP;
        return RadarDisplayFrameShape.CENTER;
    }

    /** Стабильный порядок выбора нового корня: сверху, затем по X и Z. */
    public static int compareRootPositions(BlockPos first, BlockPos second) {
        int y = Integer.compare(second.getY(), first.getY());
        if (y != 0) return y;
        int x = Integer.compare(second.getX(), first.getX());
        if (x != 0) return x;
        return Integer.compare(second.getZ(), first.getZ());
    }
}
