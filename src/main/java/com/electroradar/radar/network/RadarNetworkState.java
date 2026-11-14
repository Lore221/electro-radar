package com.electroradar.radar.network;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        final Set<TargetRef> movingStructures = new HashSet<>();
        final Map<TargetRef, LiveResult> ownStructures = new HashMap<>();
        NetworkPolicy policy;
        TargetRef selectedTarget;
        /**
         * Растёт при изменении видимого содержимого: обнаружение и забывание, новое положение
         * или тип, заморозка, выбор, регистрации, начало и конец посещения. Не растёт, когда
         * у неподвижной наблюдаемой цели обновляются только sampledTick и lastSuccessfulVisitTick.
         */
        long contentVersion;
        /** Номер текущего окна этой сети; {@code -1}, пока окно ещё не начиналось. */
        long slotSerial = -1;
        long windowStartTick = -1;
        long windowEndTick = -1;
        NetworkRuntime(NetworkId id) { this.id = id; }

        boolean windowStarted() { return slotSerial >= 0; }

        TargetEntry getOrCreateTarget(TargetRef target) {
            return targets.computeIfAbsent(target, ref -> {
                if (ref.sourceKind() == TargetRef.SourceKind.MOVING_STRUCTURE) movingStructures.add(ref);
                return new TargetEntry();
            });
        }

        void removeTargetSource(EndpointKey endpoint, TargetRef target, SourceLink link) {
            TargetEntry entry = targets.get(target);
            if (entry == null) return;
            entry.sources.remove(endpoint,link);
            if (entry.sources.isEmpty() && targets.remove(target,entry)) movingStructures.remove(target);
        }
    }

    /** Состояние посещения сектора одним источником. Переходы выполняет только менеджер. */
    enum VisitPhase {
        /** Плана нет или окно для источника ещё не наступило. */
        IDLE,
        /** Текущее окно пропущено (неготовность, ошибка, позднее начало); ждём следующее. */
        SKIPPED,
        /** Поиск выполнен; начальный набор измеряется в фазе сети и сразу после поиска. */
        OBSERVING
    }

    /** Одно неизменяемое окно источника, в котором могут наблюдаться несколько зон. */
    record VisitWindow(java.util.List<Integer> activeSectorIndices, long startTick, long endTick,
                       long fadeDurationTicks, long geometryRevision) {
        VisitWindow {
            activeSectorIndices = java.util.List.copyOf(activeSectorIndices);
        }

        boolean sameWindow(ObservationWindowSchedule.ObservationWindow window, long geometry) {
            return activeSectorIndices.equals(window.activeSectorIndices())
                    && startTick == window.startTick() && endTick == window.endTick()
                    // Затухание закреплено за начатым посещением. Reload D может изменить
                    // расчёт следующего fade, но не должен прервать собственное длинное окно.
                    && geometryRevision == geometry;
        }
    }

    /** Независимые права, начальный набор UUID и диагностика одной геометрической зоны. */
    static final class ZoneVisit {
        final int sectorIndex;
        final VisitWindow window;
        final TreeSet<TargetRef> targets = new TreeSet<>(TARGET_ORDER);
        final VisitMetrics metrics = new VisitMetrics();
        VisitPhase phase = VisitPhase.IDLE;

        ZoneVisit(int sectorIndex, VisitWindow window) {
            this.sectorIndex = sectorIndex;
            this.window = window;
        }

        boolean observing() { return phase == VisitPhase.OBSERVING; }
    }

    /** Runtime одного источника и состояние посещения каждой текущей активной зоны. */
    static final class RadarState {
        final RegistrationToken token;
        /** Номер окна сети, с которого источник участвует; регистрация посреди окна ждёт следующего. */
        final long participatesFromSlot;
        final RadarObservationSource source;
        final Map<TargetRef, SourceLink> detections = new HashMap<>();
        final TargetCategoryIndex categories = new TargetCategoryIndex();
        final Map<Integer, ZoneVisit> zoneVisits = new HashMap<>();
        RadarObservationPlan plan;
        /** Проверенная при подготовке плана кратность; исключения SPI не попадают в часы сети. */
        int networkWindowMultiple = 1;
        /** Последнее начатое (или пропущенное) окно источника; {@code null} до первого окна. */
        VisitWindow visitWindow;
        RadarState(RegistrationToken token, long participatesFromSlot, RadarObservationSource source) {
            this.token = token;
            this.participatesFromSlot = participatesFromSlot;
            this.source = source;
        }

        boolean observing() { return zoneVisits.values().stream().anyMatch(ZoneVisit::observing); }

        boolean publishMeasurement(TargetRef target, SectorConfirmation confirmation, LiveResult measurement,
                                   Set<TargetCategory> nextCategories) {
            Set<TargetCategory> immutable = Set.copyOf(nextCategories);
            boolean changed = !measurement.sameVisibleState(confirmation.measurement)
                    || !immutable.equals(confirmation.categories);
            categories.replace(target, confirmation.categories, immutable);
            confirmation.categories = immutable;
            confirmation.measurement = measurement;
            return changed;
        }

        boolean removeConfirmation(TargetRef target, int sector) {
            SourceLink link = detections.get(target);
            SectorConfirmation removed = link == null ? null : link.sectors.remove(sector);
            if (removed == null) return false;
            removeCategories(target, removed);
            return true;
        }

        private void removeCategories(TargetRef target, SectorConfirmation confirmation) {
            categories.replace(target, confirmation.categories, Set.of());
            confirmation.categories = Set.of();
        }

        int forgetFadedConfirmations(TargetRef target, long serverTick) {
            SourceLink link = detections.get(target);
            if (link == null) return 0;
            int before = link.sectors.size();
            link.sectors.values().removeIf(confirmation -> {
                if (!confirmation.faded(serverTick)) return false;
                removeCategories(target, confirmation);
                return true;
            });
            return before - link.sectors.size();
        }
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
        /** Частота поиска может быть выше прежней частоты учёта промахов памяти. */
        long nextMissTick;
        Optional<ObservationClassification> classification = Optional.empty();
        Set<TargetCategory> categories = Set.of();
        LiveResult measurement;
        long lastSuccessfulVisitTick = -1;

        boolean faded(long serverTick) {
            if (measurement == null) return true;
            return !measurement.observing() && measurement.freezeTick() >= 0
                    && serverTick - measurement.freezeTick() >= measurement.fadeDurationTicks();
        }
    }

    record AccessibleRadar(EndpointKey endpoint, RadarState state) { }
}
