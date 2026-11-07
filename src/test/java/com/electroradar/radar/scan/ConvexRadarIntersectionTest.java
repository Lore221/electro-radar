package com.electroradar.radar.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.spatial.SpatialTransform;
import com.electroradar.radar.visual.RadarVisualFadeStyle;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

class ConvexRadarIntersectionTest {
    private static final RadarVisualProfile PROFILE = RadarVisualProfile.triangularFan(
            ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO, 0, 64, 0,
            100, 0, RadarVisualProfile.FULL_TURN, 4, 1, RadarVisualFadeStyle.SECTOR);
    private static final SectorRadarScanArea AREA = new SectorRadarScanArea(new AABB(-100, 60, -100, 100, 70, 100),
            60, 70, PROFILE.coverage().sectors().stream().flatMap(sector -> sector.triangles().stream()).toList());

    @Test void edgeOfLongStructureProducesAnchorInsideCoverageAndHitbox() {
        assertAnchor(new AABB(90, 62, -1, 130, 66, 1), SpatialTransform.IDENTITY, true);
    }

    @Test void radarPrismEntirelyInsideLargeBoxStillProducesAnchor() {
        assertAnchor(new AABB(-200, 0, -200, 200, 100, 200), SpatialTransform.IDENTITY, true);
    }

    @Test void touchingCornerIsIncludedAndSeparatedBoxIsExcluded() {
        assertAnchor(new AABB(100, 62, 0, 110, 66, 10), SpatialTransform.IDENTITY, true);
        assertAnchor(new AABB(101, 62, 0, 110, 66, 10), SpatialTransform.IDENTITY, false);
        assertAnchor(new AABB(-1, 71, -1, 1, 72, 1), SpatialTransform.IDENTITY, false);
    }

    @Test void zeroHeightRadarStillClipsToItsHorizontalFootprint() {
        var a = new RadarVisualGeometry.Point(0, 0);
        var b = new RadarVisualGeometry.Point(10, 0);
        var c = new RadarVisualGeometry.Point(0, 10);
        var flat = new SectorRadarScanArea(new AABB(0, 0, 0, 10, 0, 10), 0, 0,
                List.of(new RadarVisualGeometry.Triangle(a, b, c)));
        var box = new AABB(9, -1, 0, 20, 1, 1);
        assertTrue(ConvexRadarIntersection.intersects(flat, SpatialTransform.IDENTITY.corners(box)));
        Vec3 point = ConvexRadarIntersection.intersectionPoint(flat, SpatialTransform.IDENTITY.corners(box)).orElseThrow();
        assertEquals(0, point.y, 1.0E-8);
        assertTrue(point.x >= 9 && point.z >= 0 && point.x + point.z <= 10 + 1.0E-8, () -> "outside footprint: " + point);
    }

    @Test void randomizedYawPitchAndRollAgreeWithFullPhysicalIntersection() {
        Random random = new Random(20261107);
        for (int i = 0; i < 1_000; i++) {
            double yaw = random.nextDouble() * Math.PI * 2;
            double pitch = random.nextDouble() * Math.PI;
            double roll = random.nextDouble() * Math.PI * 2;
            Vec3 x = new Vec3(Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
            Vec3 y0 = new Vec3(-Math.cos(yaw) * Math.sin(pitch), Math.cos(pitch), -Math.sin(yaw) * Math.sin(pitch));
            Vec3 z0 = x.cross(y0);
            Vec3 y = y0.scale(Math.cos(roll)).add(z0.scale(Math.sin(roll)));
            Vec3 z = x.cross(y);
            SpatialTransform transform = new SpatialTransform(Vec3.ZERO,
                    new Vec3(random.nextDouble() * 240 - 120, 60 + random.nextDouble() * 15, random.nextDouble() * 240 - 120), x, y, z);
            AABB box = new AABB(-20, -2, -3, 20, 2, 3);
            assertAnchor(box, transform, ConvexRadarIntersection.intersects(AREA, transform.corners(box)));
        }
    }

    @Test void farWorldCoordinatesDoNotLoseTheIntersection() {
        Vec3 shift = new Vec3(20_000_000, 0, -20_000_000);
        var triangles = AREA.footprint().stream().map(t -> new RadarVisualGeometry.Triangle(
                shifted(t.a(), shift), shifted(t.b(), shift), shifted(t.c(), shift))).toList();
        var farArea = new SectorRadarScanArea(AREA.queryBounds().move(shift), 60, 70, triangles);
        var transform = new SpatialTransform(Vec3.ZERO, shift, SpatialTransform.IDENTITY.x(),
                SpatialTransform.IDENTITY.y(), SpatialTransform.IDENTITY.z());
        AABB box = new AABB(90, 62, -1, 130, 66, 1);
        Vec3 point = ConvexRadarIntersection.intersectionPoint(farArea, transform.corners(box)).orElseThrow();
        assertTrue(box.inflate(1.0E-7).contains(transform.toLocal(point)));
        assertTrue(RadarVisualGeometry.sectorIndex(PROFILE, point.x - shift.x, point.z - shift.z) >= 0);
    }

    private static RadarVisualGeometry.Point shifted(RadarVisualGeometry.Point point, Vec3 shift) {
        return new RadarVisualGeometry.Point(point.x() + shift.x, point.z() + shift.z);
    }

    private static void assertAnchor(AABB box, SpatialTransform transform, boolean expected) {
        var point = ConvexRadarIntersection.intersectionPoint(AREA, transform.corners(box));
        assertEquals(expected, point.isPresent(), () -> "box=" + box + " transform=" + transform);
        point.ifPresent(p -> {
            assertTrue(box.inflate(1.0E-7).contains(transform.toLocal(p)), () -> "anchor outside physical box: " + p);
            assertTrue(p.y >= 60 - 1.0E-7 && p.y <= 70 + 1.0E-7);
            assertTrue(RadarVisualGeometry.sectorIndex(PROFILE, p.x, p.z) >= 0, () -> "anchor outside coverage: " + p);
        });
    }
}
