package com.electroradar.radar.controller;

import java.util.ArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.scan.SectorRadarScanArea;
import com.electroradar.radar.visual.RadarVisualAnimation;
import com.electroradar.radar.visual.RadarVisualGeometry;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Чистое преобразование снимка конфигурации в общую серверную и визуальную геометрию. */
final class RadarAreaGeometry {
    /** AABB/entity-section query is strict at faces; exact SAT/Y tests accept EPSILON contacts. */
    private static final double COARSE_QUERY_MARGIN = 1.0E-7D;

    private RadarAreaGeometry() {
    }

    public static GeometrySnapshot create(
            ResourceLocation dimension,
            BlockPos controllerPos,
            Direction facing,
            RadarMode mode,
            int panelCount,
            RadarServerConfig.Snapshot parameters
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
        RadarVisualProfile profile = RadarVisualProfile.circularSector(
                dimension, controllerPos, centerX, centerY, centerZ,
                range, startAngle, sweep, parameters.visualParts(), RadarVisualAnimation.SECTOR_SWEEP);
        // visualParts только делит канонический многоугольник на части анимации. Все эти
        // треугольники вместе имеют одну и ту же границу с outline и при любом числе частей.
        ArrayList<RadarVisualGeometry.Triangle> triangles = new ArrayList<>();
        for (RadarVisualGeometry.Sector sector : profile.coverage().sectors()) {
            triangles.addAll(sector.triangles());
        }
        RadarServerConfig.VerticalRange vertical = modeParameters.verticalRange();
        double worldMinY = centerY + vertical.minY();
        double worldMaxY = centerY + vertical.maxY();
        // Грубый запрос должен быть строго шире точной включительной проверки: entity-section
        // AABB иначе отсекает hitbox, касающийся граней X/Z или Y до запуска SAT.
        AABB bounds = new AABB(centerX - range, worldMinY, centerZ - range,
                centerX + range, worldMaxY, centerZ + range)
                .inflate(COARSE_QUERY_MARGIN, COARSE_QUERY_MARGIN, COARSE_QUERY_MARGIN);
        SectorRadarScanArea scanArea = new SectorRadarScanArea(bounds, worldMinY, worldMaxY, triangles);
        return new GeometrySnapshot(profile, scanArea, range, vertical.minY(), vertical.maxY(),
                modeParameters.horizontalAngle());
    }

    public record GeometrySnapshot(RadarVisualProfile profile, SectorRadarScanArea scanArea,
                                   int range, int minYOffset, int maxYOffset,
                                   double horizontalAngle) {
    }
}
