package com.electroradar.client.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import com.electroradar.radar.visual.RadarVisualFadeStyle;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Клиентская нарезка меняет только деление на подсектора, но не область радара. */
class MonitorSweepGeometryTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @Test void panelSubsectorsCoverExactlyTheServerArea() {
        RadarVisualProfile area = serverArea(-0.6D, 1.2D, 7, RadarVisualFadeStyle.SECTOR);
        for (int count : new int[] {1, 3, 5, 7, 13}) {
            RadarVisualProfile parts = MonitorSweepGeometry.partition(area, count);
            assertEquals(count, parts.sectorCount());
            assertEquals(area.coverage().outline(), parts.coverage().outline());
            assertEquals(area(area), area(parts), 1.0E-6D);
            assertSamePoints(area, parts);
        }
    }

    @Test void overviewSplitsTheFullTurnWithASeparatorPerSubsector() {
        RadarVisualProfile area = serverArea(-Math.PI / 3.0D, RadarVisualProfile.FULL_TURN, 18, RadarVisualFadeStyle.OVERVIEW);
        RadarVisualProfile parts = MonitorSweepGeometry.partition(area, 18);
        assertEquals(18, parts.sectorCount());
        assertEquals(18, parts.coverage().separators().size());
        assertEquals(area(area), area(parts), 1.0E-6D);
        double expected = area(area) / 18.0D;
        for (RadarVisualGeometry.Sector sector : parts.coverage().sectors()) {
            assertEquals(expected, area(sector.triangles()), expected * 1.0E-9D);
        }
        assertSamePoints(area, parts);
    }

    /** Как RadarAreaGeometry: опорный веер сохраняет контур, но наблюдаемая область одна. */
    private static RadarVisualProfile serverArea(double start, double sweep, int triangles, RadarVisualFadeStyle style) {
        RadarVisualProfile fan = RadarVisualProfile.triangularFan(OVERWORLD, BlockPos.ZERO, 0.5D, 64.0D, 0.5D,
                100.0D, start, sweep, triangles, 1, style);
        List<RadarVisualGeometry.Triangle> all = fan.coverage().sectors().stream()
                .flatMap(sector -> sector.triangles().stream()).toList();
        var coverage = new RadarVisualGeometry.Coverage(fan.coverage().outline(), List.of(),
                List.of(new RadarVisualGeometry.Sector(0, all, fan.coverage().outline())));
        return new RadarVisualProfile(fan.dimension(), fan.radarPosition(), fan.centerX(), fan.centerY(), fan.centerZ(),
                coverage, fan.animation(), fan.sectorOrderVariant(), fan.inactiveSectorFadeStyle());
    }

    /** Точка внутри области попадает в подсектор, точка снаружи — никуда. */
    private static void assertSamePoints(RadarVisualProfile area, RadarVisualProfile parts) {
        Random random = new Random(19);
        for (int i = 0; i < 5_000; i++) {
            double x = 0.5D + (random.nextDouble() * 2.0D - 1.0D) * 101.0D;
            double z = 0.5D + (random.nextDouble() * 2.0D - 1.0D) * 101.0D;
            boolean inside = RadarVisualGeometry.sectorIndex(area, x, z) >= 0;
            assertEquals(inside, RadarVisualGeometry.sectorIndex(parts, x, z) >= 0, () -> "x=" + x + " z=" + z);
        }
        assertTrue(parts.coverage().sectors().stream().allMatch(sector -> area(sector.triangles()) > 0.0D));
    }

    private static double area(RadarVisualProfile profile) {
        return profile.coverage().sectors().stream().mapToDouble(sector -> area(sector.triangles())).sum();
    }

    private static double area(List<RadarVisualGeometry.Triangle> triangles) {
        double sum = 0.0D;
        for (RadarVisualGeometry.Triangle t : triangles) {
            sum += Math.abs((t.b().x() - t.a().x()) * (t.c().z() - t.a().z())
                    - (t.c().x() - t.a().x()) * (t.b().z() - t.a().z())) / 2.0D;
        }
        return sum;
    }
}
