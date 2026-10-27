package com.electroradar.radar.network;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/** Изменяемые таблицы сетевого ядра; все поля принадлежат основному серверному потоку. */
final class RadarNetworkState {
    static final Comparator<EndpointKey> ENDPOINT_ORDER = RadarNetworkState::compareEndpointKeys;
    static final Comparator<TargetRef> TARGET_ORDER = Comparator
            .comparing((TargetRef target) -> target.dimension().location().toString())
            .thenComparing(target -> target.sourceKind().name())
            .thenComparing(target -> target.id().toString());

    private RadarNetworkState() { }

    private static int compareEndpointKeys(EndpointKey left, EndpointKey right) {
        int dimensionOrder = left.dimension().location().compareTo(right.dimension().location());
        if (dimensionOrder != 0) return dimensionOrder;
        if (left instanceof EndpointKey.Static leftStatic) {
            if (right instanceof EndpointKey.Moving) return -1;
            return leftStatic.position().compareTo(((EndpointKey.Static) right).position());
        }
        if (right instanceof EndpointKey.Static) return 1;
        EndpointKey.Moving leftMoving = (EndpointKey.Moving) left;
        EndpointKey.Moving rightMoving = (EndpointKey.Moving) right;
        int structureOrder = leftMoving.structureId().compareTo(rightMoving.structureId());
        return structureOrder != 0 ? structureOrder
                : leftMoving.localPosition().compareTo(rightMoving.localPosition());
    }

    static final class Registration {
        final NetworkId networkId;
        final EndpointRole role;
        final RegistrationToken token;
        Registration(NetworkId networkId, EndpointRole role, RegistrationToken token) {
            this.networkId = networkId;
            this.role = role;
            this.token = token;
        }
    }

    static final class NetworkRuntime {
        final NetworkId id;
        final Map<EndpointKey, RadarState> radars = new TreeMap<>(ENDPOINT_ORDER);
        final Map<TargetRef, TargetEntry> targets = new HashMap<>();
        NetworkPolicy policy;
        long publishedEpoch;
        long revision;
        long measurementRevision;
        TargetRef selectedTarget;
        NetworkRuntime(NetworkId id) { this.id = id; }
    }

    /** Runtime одного источника и начальный набор UUID его единственного текущего посещения. */
    static final class RadarState {
        final RegistrationToken token;
        final long participatesFromSlot;
        final RadarObservationSource source;
        final Map<TargetRef, SourceLink> detections = new HashMap<>();
        final TreeSet<TargetRef> visitTargets = new TreeSet<>(TARGET_ORDER);
        RadarObservationPlan plan;
        int activeSectorIndex = -1;
        long slotSerial = -1;
        int scheduledSectorIndex = -1;
        long windowStartTick = -1;
        long windowEndTick = -1;
        long fadeDurationTicks;
        long geometryRevision = -1;
        boolean windowAttempted;
        String searchOutcome = "not_started";
        int spatialQueries;
        int candidates;
        int acceptedHitboxes;
        int foundTargets;
        int knownChecks;
        long searchDurationNanos;
        long checkDurationNanos;
        long uuidLookups;
        long cacheHits;
        RadarState(RegistrationToken token, long participatesFromSlot, RadarObservationSource source) {
            this.token = token;
            this.participatesFromSlot = participatesFromSlot;
            this.source = source;
        }
    }

    /** Каноническая цель и обратный индекс радаров, у которых есть секторное подтверждение. */
    static final class TargetEntry {
        final Map<EndpointKey, SourceLink> sources = new TreeMap<>(ENDPOINT_ORDER);
    }

    /** Все геометрические подтверждения одной пары «источник–цель»; измерения не глобальны. */
    static final class SourceLink {
        final Map<Integer, SectorConfirmation> sectors = new TreeMap<>();
    }

    static final class SectorConfirmation {
        int missedVisits;
        Optional<ObservationClassification> classification = java.util.Optional.empty();
        LiveResult measurement;
        long lastSuccessfulVisitTick = -1;
    }

    record AccessibleRadar(EndpointKey endpoint, RadarState state) { }
}
