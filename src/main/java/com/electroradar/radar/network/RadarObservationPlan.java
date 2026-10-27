package com.electroradar.radar.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.scan.RadarScanArea;
import com.electroradar.radar.visual.RadarSectorOrder;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Неизменяемая геометрия и политика окон одного источника. */
public record RadarObservationPlan(ResourceLocation dimension, RadarVisualProfile profile,
                                   List<Sector> sectors, int sectorOrderVariant,
                                   long geometryRevision, long configRevision,
                                   ObservationWindowSchedule schedule) {
    public RadarObservationPlan {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(schedule, "schedule");
        sectors = List.copyOf(sectors);
        if (!profile.dimension().equals(dimension) || !RadarSectorOrder.validVariant(sectorOrderVariant)
                || geometryRevision < 0 || configRevision < 0
                || sectors.size() != profile.sectorCount()) {
            throw new IllegalArgumentException("Observation sectors must match the visual profile");
        }
        Set<Integer> indexes = new HashSet<>();
        for (int i = 0; i < sectors.size(); i++) {
            Sector sector = sectors.get(i);
            if (sector.index() != i || !indexes.add(sector.index())) {
                throw new IllegalArgumentException("Sector geometry must stay in stable index order");
            }
        }
    }

    /** Текущий семисекторный контроллер по умолчанию следует общей конфигурационной фазе. */
    public RadarObservationPlan(ResourceLocation dimension, RadarVisualProfile profile,
                                List<Sector> sectors, int sectorOrderVariant,
                                long geometryRevision, long configRevision) {
        this(dimension, profile, sectors, sectorOrderVariant, geometryRevision, configRevision,
                ObservationWindowSchedule.globalSectorCycle(profile.sectorCount(), sectorOrderVariant));
    }

    /** Геометрический индекс не меняется при выборе другого порядка обхода. */
    public record Sector(int index, RadarScanArea area) {
        public Sector {
            Objects.requireNonNull(area, "area");
            if (index < 0 || index >= RadarVisualProfile.MAX_SECTOR_COUNT) {
                throw new IllegalArgumentException("Invalid sector index");
            }
        }
    }
}
