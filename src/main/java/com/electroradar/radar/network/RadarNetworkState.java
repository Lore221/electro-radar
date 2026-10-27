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
    static final Comparator<TargetRef> TARGET_ORDER = TargetRef.ORDER;

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

    /**
     * Runtime одной сети: её радары, общая таблица целей, собственное окно и единственная
     * версия видимого состояния. Окно есть только у сети, где есть хотя бы один радар.
     */
    static final class NetworkRuntime {
        final NetworkId id;
        final Map<EndpointKey, RadarState> radars = new TreeMap<>(ENDPOINT_ORDER);
        final Map<TargetRef, TargetEntry> targets = new HashMap<>();
        NetworkPolicy policy;
        TargetRef selectedTarget;
        /**
         * Растёт при любом изменении, которое может увидеть потребитель: обнаружение и забывание,
         * видимое изменение измерения, заморозка, выбор, регистрации, начало и конец посещения.
         * Потребитель, у которого версия не изменилась, может не перечитывать цели.
         */
        long version;
        /** Номер текущего окна этой сети; {@code -1}, пока окно ещё не начиналось. */
        long slotSerial = -1;
        long windowStartTick = -1;
        long windowEndTick = -1;
        NetworkRuntime(NetworkId id) { this.id = id; }

        boolean windowStarted() { return slotSerial >= 0; }
    }

    /** Состояние посещения сектора одним источником. Переходы выполняет только менеджер. */
    enum VisitPhase {
        /** Плана нет или окно для источника ещё не наступило. */
        IDLE,
        /** Текущее окно пропущено (неготовность, ошибка, позднее начало); ждём следующее. */
        SKIPPED,
        /** Поиск в начале окна выполнен, начальный набор проверяется каждый тик. */
        OBSERVING
    }

    /** Неизменяемые параметры одного посещения: какое окно и какой геометрический сектор. */
    record Visit(long slotSerial, int sectorIndex, long startTick, long endTick,
                 long fadeDurationTicks, long geometryRevision) {
        boolean sameWindow(long slot, ObservationWindowSchedule.ObservationWindow window, long geometry) {
            return slotSerial == slot && sectorIndex == window.activeSectorIndex()
                    && startTick == window.startTick() && endTick == window.endTick()
                    && geometryRevision == geometry;
        }
    }

    /** Runtime одного источника и начальный набор UUID его единственного текущего посещения. */
    static final class RadarState {
        final RegistrationToken token;
        /** Номер окна сети, с которого источник участвует; регистрация посреди окна ждёт следующего. */
        final long participatesFromSlot;
        final RadarObservationSource source;
        final Map<TargetRef, SourceLink> detections = new HashMap<>();
        final TreeSet<TargetRef> visitTargets = new TreeSet<>(TARGET_ORDER);
        final VisitMetrics metrics = new VisitMetrics();
        RadarObservationPlan plan;
        VisitPhase phase = VisitPhase.IDLE;
        /** Последнее начатое (или пропущенное) посещение; {@code null} до первого окна. */
        Visit visit;
        RadarState(RegistrationToken token, long participatesFromSlot, RadarObservationSource source) {
            this.token = token;
            this.participatesFromSlot = participatesFromSlot;
            this.source = source;
        }

        boolean observing() { return phase == VisitPhase.OBSERVING; }
    }

    /** Только диагностические счётчики одного посещения; на поведение не влияют. */
    static final class VisitMetrics {
        String outcome = "not_started";
        int spatialQueries;
        int candidates;
        int acceptedHitboxes;
        int foundTargets;
        int knownChecks;
        long searchDurationNanos;
        long checkDurationNanos;
        long uuidLookups;
        long cacheHits;

        void reset() {
            outcome = "not_started";
            spatialQueries = 0;
            candidates = 0;
            acceptedHitboxes = 0;
            foundTargets = 0;
            knownChecks = 0;
            searchDurationNanos = 0L;
            checkDurationNanos = 0L;
            uuidLookups = 0L;
            cacheHits = 0L;
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
        Optional<ObservationClassification> classification = Optional.empty();
        LiveResult measurement;
        long lastSuccessfulVisitTick = -1;
    }

    record AccessibleRadar(EndpointKey endpoint, RadarState state) { }
}
