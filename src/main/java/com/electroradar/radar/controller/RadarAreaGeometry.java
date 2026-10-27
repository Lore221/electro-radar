package com.electroradar.radar.controller;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.scan.SectorRadarScanArea;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Чистое преобразование снимка конфигурации в общую серверную и визуальную геометрию. */
final class RadarAreaGeometry {
    static final int SECTOR_COUNT = 7;
    /** Грубый поиск строгий, точная проверка допускает касание с погрешностью. */
    private static final double COARSE_QUERY_MARGIN = 1.0E-7D;

    private RadarAreaGeometry() {
    }

    public static GeometrySnapshot create(
            ResourceLocation dimension,
            BlockPos controllerPos,
            Direction facing,
            RadarMode mode,
            int panelCount,
            RadarServerConfig.Snapshot parameters,
            int sectorOrderVariant
    ) {
        RadarServerConfig.RangeParameters rangeParameters = parameters.range();
        RadarServerConfig.ModeParameters modeParameters = mode.parameters(parameters);
        double centerX = controllerPos.getX() + 0.5D;
        double centerY = controllerPos.getY() + 0.5D;
        double centerZ = controllerPos.getZ() + 0.5D;
        int boundedPanelCount = Math.min(Math.max(0, panelCount), parameters.maxPanels());
        double unbounded = ((double) rangeParameters.baseRange()
                + (double) boundedPanelCount * rangeParameters.rangePerPanel())
                * rangeParameters.globalMultiplier() * modeParameters.rangeMultiplier();
        int range;
        if (!Double.isFinite(unbounded) || unbounded >= rangeParameters.maxRange()) {
            range = rangeParameters.maxRange();
        } else {
            range = Math.max(0, (int) Math.floor(unbounded));
        }

        double sweep = Math.toRadians(modeParameters.horizontalAngle());
        double forwardAngle = Math.atan2(facing.getStepX(), -facing.getStepZ());
        double startAngle = forwardAngle - sweep / 2.0D;
        RadarVisualProfile profile = RadarVisualProfile.triangularFan(
                dimension, controllerPos, centerX, centerY, centerZ,
                range, startAngle, sweep, SECTOR_COUNT, sectorOrderVariant);
        RadarServerConfig.VerticalRange vertical = modeParameters.verticalRange();
        double worldMinY = centerY + vertical.minY();
        double worldMaxY = centerY + vertical.maxY();
        ArrayList<ScanSector> sectors = new ArrayList<>(SECTOR_COUNT);
        for (RadarVisualGeometry.Sector sector : profile.coverage().sectors()) {
            sectors.add(new ScanSector(sector.index(), sector,
                    area(sector.triangles(), centerX, centerZ, worldMinY, worldMaxY)));
        }
        return new GeometrySnapshot(profile, sectors, range, vertical.minY(), vertical.maxY(),
                modeParameters.horizontalAngle());
    }

    private static SectorRadarScanArea area(List<RadarVisualGeometry.Triangle> triangles,
                                             double centerX, double centerZ, double minY, double maxY) {
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (RadarVisualGeometry.Triangle triangle : triangles) {
            for (RadarVisualGeometry.Point point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minZ = Math.min(minZ, point.z());
                maxZ = Math.max(maxZ, point.z());
            }
        }
        if (triangles.isEmpty()) minX = maxX = centerX;
        if (triangles.isEmpty()) minZ = maxZ = centerZ;
        // Запас обязателен: грубый поиск строгий, а точная проверка включает касание.
        AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ)
                .inflate(COARSE_QUERY_MARGIN, COARSE_QUERY_MARGIN, COARSE_QUERY_MARGIN);
        return new SectorRadarScanArea(bounds, minY, maxY, triangles);
    }

    /** Индекс и треугольники принадлежат той же части Coverage, что отображает монитор. */
    public record ScanSector(int index, RadarVisualGeometry.Sector visualSector, SectorRadarScanArea area) {
    }

    /** Неизменяемая геометрия; единственными областями сканирования являются семь частей Coverage. */
    public record GeometrySnapshot(RadarVisualProfile profile, List<ScanSector> sectors,
                                   int range, int minYOffset, int maxYOffset,
                                   double horizontalAngle) {
        public GeometrySnapshot {
            sectors = List.copyOf(sectors);
            if (sectors.size() != SECTOR_COUNT) throw new IllegalArgumentException("Expected seven sectors");
        }
    }
}
