package com.electroradar.radar.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Верх карты задаётся явно; правая ось — по часовой стрелке от него. */
class RadarDisplayProjectorTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final double EPSILON = 1.0E-9D;

    @Test void northUpPutsNorthAtTheTopAndEastToTheRight() {
        assertPoint(0, 1, project(Direction.NORTH, 0, -10));
        assertPoint(1, 0, project(Direction.NORTH, 10, 0));
        assertPoint(0, -1, project(Direction.NORTH, 0, 10));
    }

    @Test void everyChosenTopIsARotationOfTheMap() {
        for (Direction up : Direction.Plane.HORIZONTAL) {
            assertPoint(0, 1, project(up, 10 * up.getStepX(), 10 * up.getStepZ()));
            Direction right = up.getClockWise();
            assertPoint(1, 0, project(up, 10 * right.getStepX(), 10 * right.getStepZ()));
        }
    }

    @Test void guiOffsetMovesTheMapCenter() {
        var point = RadarDisplayProjector.project(OVERWORLD, OVERWORLD, 0.0D, 0.0D, Direction.NORTH,
                5.0D, 0.0D, 5.0D, 0.0D, 20.0D);
        assertPoint(0, 0, point);
    }

    @Test void targetsFromAnotherDimensionAreNotProjected() {
        assertNull(RadarDisplayProjector.project(ResourceLocation.withDefaultNamespace("the_nether"), OVERWORLD,
                0.0D, 0.0D, Direction.NORTH, 1.0D, 1.0D, 0.0D, 0.0D, 20.0D));
    }

    private static RadarDisplayProjector.MapPoint project(Direction up, double x, double z) {
        return RadarDisplayProjector.project(OVERWORLD, OVERWORLD, 0.0D, 0.0D, up, x, z, 0.0D, 0.0D, 20.0D);
    }

    private static void assertPoint(double x, double y, RadarDisplayProjector.MapPoint point) {
        assertEquals(x, point.x(), EPSILON);
        assertEquals(y, point.y(), EPSILON);
    }
}
