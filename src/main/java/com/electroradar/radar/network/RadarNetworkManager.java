package com.electroradar.radar.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.AccessibleRadar;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.RadarState;
import com.electroradar.radar.network.RadarNetworkState.Registration;
import com.electroradar.radar.network.RadarNetworkState.SectorConfirmation;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;
import com.electroradar.radar.scan.EntityRadarScanner;
import com.electroradar.radar.visual.RadarSectorOrder;

/**
 * Серверный координатор общей секторной фазы и владелец регистраций, обнаружений и памяти.
 * Все переходы выполняются на серверном потоке из ServerTickEvent.Pre; читатели видят результат
 * только после завершения обработки всех источников текущего тика.
 */
public final class RadarNetworkManager {
    private static final EntityRadarScanner SCANNER = new EntityRadarScanner();
    private static final Map<MinecraftServer, RadarNetworkManager> MANAGERS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final MinecraftServer server;
    private final Map<EndpointKey, Registration> endpointIndex = new HashMap<>();
    private final Map<NetworkId, Set<EndpointKey>> endpointsByNetwork = new HashMap<>();
    private final Map<NetworkId, NetworkRuntime> networks = new HashMap<>();
    private long serverTick;
    /** Номер временного слота общий для всех сетей и измерений сервера. */
    private long slotSerial = -1;
    private long windowStartTick = -1;
    private long windowEndTick = -1;
    private long activeDurationTicks;
    private boolean phaseStarted;
    private boolean closed;

    private RadarNetworkManager(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static RadarNetworkManager forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("Radar network access must run on server thread");
        synchronized (MANAGERS) {
            RadarNetworkManager manager = MANAGERS.get(server);
            if (manager == null || manager.closed) {
                throw new IllegalStateException("Radar network manager is not active for this server");
            }
            return manager;
        }
    }

    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        RadarNetworkManager previous;
        synchronized (MANAGERS) { previous = MANAGERS.put(server, new RadarNetworkManager(server)); }
        if (previous != null && !previous.closed) previous.close();
        RadarDiagnostics.log("network_manager_started", null, null, null, 0L, "phase=global_pre_tick");
    }

    public static void onServerTick(ServerTickEvent.Pre event) {
        RadarNetworkManager manager;
        synchronized (MANAGERS) { manager = MANAGERS.get(event.getServer()); }
        if (manager != null) manager.beginServerTick();
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        RadarNetworkManager manager;
        synchronized (MANAGERS) { manager = MANAGERS.remove(event.getServer()); }
        if (manager != null && !manager.closed) {
            RadarDiagnostics.log("network_manager_stopping", null, null, null, manager.serverTick,
                    "networks=" + manager.networks.size());
            manager.close();
        }
    }

    public long currentServerTick() {
        checkActive();
        return serverTick;
    }

    /** Регистрирует источник; новый token включается только на следующей общей границе. */
    public RegistrationToken registerRadar(EndpointKey endpoint, NetworkId networkId, NetworkPolicy policy,
                                           RadarObservationSource source) {
        checkActive();
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(source, "source");
        ensureCompatiblePolicy(endpoint, networkId, policy);
        Set<NetworkId> changed = new HashSet<>();
        removePreviousRegistration(endpoint, changed);
        NetworkRuntime network = networks.computeIfAbsent(networkId, NetworkRuntime::new);
        network.policy = policy;
        RegistrationToken token = RegistrationToken.create();
        long participatesFromSlot = phaseStarted ? slotSerial + 1 : 0;
        endpointIndex.put(endpoint, new Registration(networkId, EndpointRole.RADAR, token));
        addNetworkEndpoint(networkId, endpoint);
        network.radars.put(endpoint, new RadarState(token, participatesFromSlot, source));
        changed.add(networkId);
        markChanged(changed, false);
        RadarDiagnostics.log("radar_registered", networkId, endpoint, slotSerial + 1, serverTick,
                "token=" + token.value() + " participatesFromSlot=" + participatesFromSlot
                        + " nextBoundary=" + (phaseStarted ? windowEndTick : "initial"));
        return token;
    }

    /** Совместимая сигнатура не создаёт источник без его обязательного серверного адаптера. */
    @Deprecated
    public RegistrationToken registerRadar(EndpointKey endpoint, NetworkId networkId, NetworkPolicy policy) {
        throw new IllegalArgumentException("Radar registration requires a RadarObservationSource adapter");
    }

    public RegistrationToken register(EndpointKey endpoint, NetworkId networkId, EndpointRole role) {
        checkActive();
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(role, "role");
        if (role == EndpointRole.RADAR) throw new IllegalArgumentException("Use registerRadar for radar endpoints");
        Set<NetworkId> changed = new HashSet<>();
        removePreviousRegistration(endpoint, changed);
        RegistrationToken token = RegistrationToken.create();
        endpointIndex.put(endpoint, new Registration(networkId, role, token));
        addNetworkEndpoint(networkId, endpoint);
        markChanged(changed, false);
        RadarDiagnostics.log("consumer_registered", networkId, null, null, serverTick,
                "endpoint=" + endpoint + " role=" + role + " token=" + token.value());
        return token;
    }

    public List<EndpointKey> registeredEndpoints(NetworkId networkId) {
        checkActive();
        Set<EndpointKey> endpoints = endpointsByNetwork.get(Objects.requireNonNull(networkId, "networkId"));
        return endpoints == null ? List.of() : endpoints.stream().sorted(RadarNetworkState.ENDPOINT_ORDER).toList();
    }

    /** Неизменяемая визуальная фаза источника, выровненная с реальным серверным обходом. */
    public Optional<RadarVisualWindow> visualWindowForRadar(EndpointKey radar) {
        checkActive();
        Registration registration = endpointIndex.get(radar);
        NetworkRuntime network = registration == null ? null : networks.get(registration.networkId);
        RadarState state = network == null ? null : network.radars.get(radar);
        if (registration == null || registration.role != EndpointRole.RADAR || state == null
                || !state.token.equals(registration.token) || state.plan == null || state.activeSectorIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(new RadarVisualWindow(state.plan.profile(), state.geometryRevision,
                state.slotSerial, state.activeSectorIndex, state.windowStartTick, state.windowEndTick,
                state.plan.sectorOrderVariant()));
    }

    public boolean unregister(EndpointKey endpoint, NetworkId networkId, RegistrationToken token) {
        checkActive();
        Registration registration = matchingRegistration(endpoint, networkId, token, null);
        if (registration == null) {
            RadarDiagnostics.log("registration_removal_rejected", networkId, endpoint, null, serverTick,
                    "reason=registration_token_mismatch");
            return false;
        }
        endpointIndex.remove(endpoint);
        removeNetworkEndpoint(registration.networkId, endpoint);
        Set<NetworkId> changed = new HashSet<>();
        removeRegistrationData(endpoint, registration, changed);
        markChanged(changed, registration.role == EndpointRole.RADAR);
        RadarDiagnostics.log(registration.role == EndpointRole.RADAR ? "radar_unregistered" : "consumer_unregistered",
                networkId, registration.role == EndpointRole.RADAR ? endpoint : null, null, serverTick,
                "endpoint=" + endpoint + " token=" + token.value());
        return true;
    }

    public <T> TargetSnapshot<T> queryTargets(EndpointKey consumer, NetworkId networkId,
                                              RegistrationToken token, TargetProjection<T> projection) {
        checkActive();
        Objects.requireNonNull(projection, "projection");
        Registration registration = queryRegistration(consumer, networkId, token, "query_targets");
        if (registration == null) return TargetSnapshot.empty(0, 0);
        return RadarNetworkQueries.queryTargets(consumer, registration, projection, serverTick,
                endpointIndex, networks);
    }

    public TargetSnapshot<TargetRef> queryTargetRefs(EndpointKey consumer, NetworkId networkId,
                                                      RegistrationToken token) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "query_target_refs");
        if (registration == null) return TargetSnapshot.empty(0, 0);
        return RadarNetworkQueries.queryTargetRefs(consumer, registration, serverTick,
                endpointIndex, networks);
    }

    public <T> TargetSnapshot<T> queryTargetDetails(EndpointKey consumer, NetworkId networkId,
                                                    RegistrationToken token, Collection<TargetRef> targets,
                                                    TargetProjection<T> projection) {
        checkActive();
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(projection, "projection");
        Registration registration = queryRegistration(consumer, networkId, token, "query_target_details");
        if (registration == null) return TargetSnapshot.empty(0, 0);
        return RadarNetworkQueries.queryTargetDetails(consumer, registration, targets, projection, serverTick,
                endpointIndex, networks);
    }

    public TargetSources sourcesForTarget(EndpointKey consumer, NetworkId networkId,
                                         RegistrationToken token, TargetRef target) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "sources_for_target");
        if (registration == null) return new TargetSources(target, 0, 0, List.of(), Optional.empty());
        return RadarNetworkQueries.sourcesForTarget(consumer, registration, target, serverTick,
                endpointIndex, networks);
    }

    public Optional<TargetRef> selectedTarget(EndpointKey consumer, NetworkId networkId,
                                               RegistrationToken token) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "selected_target");
        if (registration == null) return Optional.empty();
        return RadarNetworkQueries.selectedTarget(consumer, registration, serverTick, networks);
    }

    public boolean selectTarget(EndpointKey monitor, NetworkId networkId,
                                RegistrationToken token, TargetRef target) {
        checkActive();
        Registration registration = matchingRegistration(monitor, networkId, token, EndpointRole.MONITOR);
        if (registration == null) return false;
        NetworkRuntime network = networks.get(networkId);
        if (target != null) {
            if (!target.dimension().equals(monitor.dimension()) || network == null) return false;
            List<AccessibleRadar> radars = RadarNetworkQueries.accessibleRadars(monitor, registration, network, endpointIndex);
            if (!RadarNetworkQueries.hasFreshAccessibleSource(radars, target)) return false;
        }
        if (network == null) return target == null;
        if (Objects.equals(network.selectedTarget, target)) return true;
        network.selectedTarget = target;
        network.revision++;
        return true;
    }

    public boolean isCurrentConsumerRegistration(EndpointKey consumer, NetworkId networkId,
                                                 RegistrationToken token) {
        checkActive();
        Registration registration = endpointIndex.get(consumer);
        return registration != null && registration.role.canQueryNetwork()
                && registration.networkId.equals(networkId) && registration.token.equals(token);
    }

    boolean isClosed() { return closed; }

    private void beginServerTick() {
        checkActive();
        serverTick = Math.incrementExact(serverTick);
        RadarServerConfig.Snapshot parameters = RadarServerConfig.snapshot();
        boolean boundary = !phaseStarted || serverTick >= windowEndTick;
        Set<NetworkId> changedDiscoveries = new HashSet<>();
        Set<NetworkId> changedMeasurements = new HashSet<>();
        PhaseEntityCache entityCache = new PhaseEntityCache();
        try {
            if (boundary) {
                slotSerial = Math.incrementExact(slotSerial);
                windowStartTick = serverTick;
                activeDurationTicks = parameters.sectorTicks();
                windowEndTick = Math.addExact(windowStartTick, activeDurationTicks);
                phaseStarted = true;
                for (NetworkRuntime network : networks.values()) network.publishedEpoch = slotSerial + 1;
            }
            ObservationWindowSchedule.GlobalWindow globalWindow =
                    new ObservationWindowSchedule.GlobalWindow(slotSerial, windowStartTick, windowEndTick);
            for (Map.Entry<NetworkId, NetworkRuntime> networkEntry : networks.entrySet()) {
                NetworkRuntime network = networkEntry.getValue();
                for (Map.Entry<EndpointKey, RadarState> radarEntry : network.radars.entrySet()) {
                    RadarState state = radarEntry.getValue();
                    try {
                        processRadar(network, radarEntry.getKey(), state, parameters,
                                boundary, globalWindow, entityCache, changedDiscoveries, changedMeasurements);
                    } catch (RuntimeException exception) {
                        freezeCurrentVisit(network, radarEntry.getKey(), state, serverTick, changedMeasurements);
                        state.plan = null;
                        state.activeSectorIndex = -1;
                        state.visitTargets.clear();
                        RadarDiagnostics.logException("observation_source_failed", network.id,
                                radarEntry.getKey(), slotSerial + 1, serverTick,
                                "active_rights=revoked other_sources=continue", exception);
                    }
                }
            }
        } finally {
            entityCache.clear();
        }
        markChanged(changedDiscoveries, false);
        for (NetworkId id : changedMeasurements) {
            NetworkRuntime network = networks.get(id);
            if (network != null) {
                network.measurementRevision++;
            }
        }
        if (boundary) {
            RadarDiagnostics.log("observation_phase_boundary", null, null, slotSerial + 1, serverTick,
                    "window=[" + windowStartTick + "," + windowEndTick + ") duration=" + activeDurationTicks
                            + " configRevision=" + parameters.revision() + " sources=" + activeRadarCount());
        }
    }

    private void processRadar(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                              RadarServerConfig.Snapshot parameters, boolean boundary,
                              ObservationWindowSchedule.GlobalWindow globalWindow,
                              PhaseEntityCache cache, Set<NetworkId> changedDiscoveries,
                              Set<NetworkId> changedMeasurements) {
        if (state.participatesFromSlot > slotSerial) return;
        RadarObservationPlan plan = state.plan;
        if (boundary) {
            try {
                Optional<RadarObservationPlan> prepared = state.source.createObservationPlan(parameters);
                if (prepared.isEmpty()) {
                    if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
                    state.plan = null;
                    state.windowAttempted = false;
                    state.searchOutcome = "source_not_ready";
                    RadarDiagnostics.log("observation_window_skipped", network.id, endpoint,
                            slotSerial + 1, serverTick, "reason=plan_unavailable");
                    return;
                }
                plan = prepared.orElseThrow();
            } catch (RuntimeException exception) {
                if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
                state.plan = null;
                state.windowAttempted = false;
                state.searchOutcome = "plan_exception";
                RadarDiagnostics.logException("observation_plan_failed", network.id, endpoint,
                        slotSerial + 1, serverTick, "retry=next_global_boundary", exception);
                return;
            }
        } else if (plan == null) {
            return;
        }

        ObservationWindowSchedule.ObservationWindow scheduled;
        try {
            Optional<ObservationWindowSchedule.ObservationWindow> next =
                    plan.schedule().windowAt(serverTick, globalWindow);
            if (next.isEmpty()) {
                if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
                state.plan = plan;
                state.activeSectorIndex = -1;
                state.windowAttempted = false;
                state.visitTargets.clear();
                return;
            }
            scheduled = next.orElseThrow();
            if (!plan.dimension().equals(endpoint.dimension().location())
                    || scheduled.activeSectorIndex() >= plan.sectors().size()
                    || scheduled.startTick() > serverTick || scheduled.endTick() <= serverTick) {
                throw new IllegalStateException("Source schedule returned a window invalid for this phase");
            }
        } catch (RuntimeException exception) {
            if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
            state.plan = plan;
            state.activeSectorIndex = -1;
            RadarDiagnostics.logException("observation_schedule_failed", network.id, endpoint,
                    slotSerial + 1, serverTick, "active_rights=revoked retry=next_schedule_window", exception);
            return;
        }

        boolean newWindow = !state.windowAttempted || state.slotSerial != scheduled.slotSerial()
                || state.scheduledSectorIndex != scheduled.activeSectorIndex()
                || state.windowStartTick != scheduled.startTick() || state.windowEndTick != scheduled.endTick()
                || state.geometryRevision != plan.geometryRevision();
        if (!newWindow && state.activeSectorIndex < 0) {
            state.plan = plan;
            return;
        }
        if (newWindow) {
            if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
            state.plan = plan;
            state.slotSerial = scheduled.slotSerial();
            state.scheduledSectorIndex = scheduled.activeSectorIndex();
            state.windowStartTick = scheduled.startTick();
            state.windowEndTick = scheduled.endTick();
            state.fadeDurationTicks = scheduled.fadeDurationTicks();
            state.geometryRevision = plan.geometryRevision();
            state.windowAttempted = true;
            state.activeSectorIndex = -1;
            state.visitTargets.clear();
            resetWindowMetrics(state);
            if (scheduled.startTick() != serverTick) {
                state.searchOutcome = "window_start_missed";
                RadarDiagnostics.log("observation_window_skipped", network.id, endpoint,
                        scheduled.slotSerial() + 1, serverTick,
                        "reason=window_start_missed startTick=" + scheduled.startTick());
                return;
            }
        }

        RadarObservationPlan activePlan = state.plan;
        try {
            if (!state.source.isReadyForObservation(activePlan)) {
                if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
                state.activeSectorIndex = -1;
                state.searchOutcome = "source_not_ready";
                if (newWindow) {
                    RadarDiagnostics.log("observation_window_skipped", network.id, endpoint,
                            scheduled.slotSerial() + 1, serverTick, "reason=readiness_revoked");
                } else {
                    RadarDiagnostics.log("observation_revoked", network.id, endpoint,
                            scheduled.slotSerial() + 1, serverTick, "reason=source_not_ready");
                }
                return;
            }
        } catch (RuntimeException exception) {
            if (state.activeSectorIndex >= 0) closeVisit(network, endpoint, state, serverTick, changedMeasurements);
            state.activeSectorIndex = -1;
            RadarDiagnostics.logException("observation_readiness_failed", network.id, endpoint,
                    scheduled.slotSerial() + 1, serverTick, "active_rights=revoked", exception);
            return;
        }

        if (newWindow) {
            state.activeSectorIndex = scheduled.activeSectorIndex();
            try {
                searchSector(network, endpoint, state, cache, changedDiscoveries);
            } catch (RuntimeException exception) {
                state.searchOutcome = "search_exception";
                closeVisit(network, endpoint, state, serverTick, changedMeasurements);
                RadarDiagnostics.logException("observation_boundary_failed", network.id, endpoint,
                        scheduled.slotSerial() + 1, serverTick,
                        "retry=next_schedule_window active_rights=revoked", exception);
                return;
            }
        }
        if (state.plan != null && state.activeSectorIndex >= 0)
            updateKnownTargets(network, endpoint, state, cache, changedMeasurements);
    }

    /** Один запрос на входе; ошибки не считаются пустыми успешными поисками. */
    private void searchSector(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                              PhaseEntityCache cache, Set<NetworkId> changedDiscoveries) {
        RadarObservationPlan.Sector sector = state.plan.sectors().get(state.activeSectorIndex);
        if (sector.area().isEmpty()) {
            state.searchOutcome = "empty_geometry";
            applySuccessfulSearch(network, endpoint, state, Map.of(), changedDiscoveries);
            return;
        }
        ServerLevel level = server.getLevel(endpoint.dimension());
        if (level == null) {
            state.searchOutcome = "dimension_unloaded";
            RadarDiagnostics.log("observation_search_unavailable", network.id, endpoint, state.slotSerial + 1,
                    serverTick, "reason=dimension_unloaded");
            return;
        }
        long searchStarted = System.nanoTime();
        EntityRadarScanner.EntitySearchResult result = SCANNER.scanEntities(
                level, sector.area(), entity -> !(entity instanceof net.minecraft.world.entity.item.ItemEntity));
        long searchDuration = System.nanoTime() - searchStarted;
        state.searchOutcome = "search_succeeded";
        state.spatialQueries++;
        state.candidates += result.candidateCount();
        state.acceptedHitboxes += result.acceptedHitboxCount();
        state.foundTargets += result.entities().size();
        state.searchDurationNanos += searchDuration;
        for (Map.Entry<TargetRef, Entity> found : result.entities().entrySet()) cache.remember(found.getKey(), found.getValue());
        applySuccessfulSearch(network, endpoint, state, result.entities(), changedDiscoveries);
    }

    private void applySuccessfulSearch(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                       Map<TargetRef, Entity> found, Set<NetworkId> changedDiscoveries) {
        int sectorIndex = state.activeSectorIndex;
        state.visitTargets.clear();
        for (Map.Entry<TargetRef, Entity> candidate : found.entrySet()) {
            TargetRef target = candidate.getKey();
            if (!target.dimension().location().equals(endpoint.dimension().location())) continue;
            SourceLink link = state.detections.computeIfAbsent(target, ignored -> {
                TargetEntry entry = network.targets.computeIfAbsent(target, unused -> new TargetEntry());
                SourceLink created = new SourceLink();
                entry.sources.put(endpoint, created);
                changedDiscoveries.add(network.id);
                return created;
            });
            SectorConfirmation confirmation = link.sectors.computeIfAbsent(sectorIndex, ignored -> {
                changedDiscoveries.add(network.id);
                return new SectorConfirmation();
            });
            confirmation.missedVisits = 0;
            confirmation.lastSuccessfulVisitTick = serverTick;
            state.visitTargets.add(target);
        }
        // Только успешный поиск меняет пропуски, только у текущего геометрического индекса.
        List<TargetRef> forgotten = new ArrayList<>();
        for (Map.Entry<TargetRef, SourceLink> detection : state.detections.entrySet()) {
            SectorConfirmation confirmation = detection.getValue().sectors.get(sectorIndex);
            if (confirmation == null || found.containsKey(detection.getKey())) continue;
            confirmation.missedVisits++;
            if (confirmation.missedVisits >= network.policy.forgetAfterMisses()) {
                forgotten.add(detection.getKey());
            }
        }
        for (TargetRef target : forgotten) {
            removeSectorConfirmation(network, endpoint, state, target, sectorIndex, changedDiscoveries);
            RadarDiagnostics.log("sector_confirmation_forgotten", network.id, endpoint, state.slotSerial + 1,
                    serverTick, "target=" + target + " sector=" + sectorIndex + " reason=miss_limit");
        }
    }

    private void updateKnownTargets(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                    PhaseEntityCache cache, Set<NetworkId> changedMeasurements) {
        RadarObservationPlan.Sector sector = state.plan.sectors().get(state.activeSectorIndex);
        if (sector.area().isEmpty()) return;
        long startedAt = System.nanoTime();
        long lookupsBefore = cache.uuidLookups;
        long hitsBefore = cache.cacheHits;
        int checks = 0;
        for (TargetRef target : List.copyOf(state.visitTargets)) {
            checks++;
            SourceLink link = state.detections.get(target);
            SectorConfirmation confirmation = link == null ? null : link.sectors.get(state.activeSectorIndex);
            if (confirmation == null) continue;
            try {
                Entity entity = cache.resolve(target);
                if (entity == null || !EntityRadarScanner.anyHitboxInside(entity, sector.area())) {
                    freezeConfirmation(confirmation, serverTick, state.fadeDurationTicks, changedMeasurements, network.id);
                    continue;
                }
                LiveResult next = LiveResult.observed(slotSerial + 1, serverTick,
                        entity.position(), entity.getType());
                confirmation.measurement = next;
                confirmation.lastSuccessfulVisitTick = serverTick;
                changedMeasurements.add(network.id);
            } catch (RuntimeException exception) {
                freezeConfirmation(confirmation, serverTick, state.fadeDurationTicks, changedMeasurements, network.id);
                RadarDiagnostics.logException("known_target_check_failed", network.id, endpoint,
                        state.slotSerial + 1, serverTick, "target=" + target + " sector=" + state.activeSectorIndex,
                        exception);
            }
        }
        state.knownChecks += checks;
        state.uuidLookups += cache.uuidLookups - lookupsBefore;
        state.cacheHits += cache.cacheHits - hitsBefore;
        state.checkDurationNanos += System.nanoTime() - startedAt;
    }

    private void closeVisit(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                            long tick, Set<NetworkId> changedMeasurements) {
        if (state.activeSectorIndex >= 0) logObservationSummary(network, endpoint, state, tick);
        freezeCurrentVisit(network, endpoint, state, tick, changedMeasurements);
        state.visitTargets.clear();
        state.activeSectorIndex = -1;
    }

    private void freezeCurrentVisit(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                    long tick, Set<NetworkId> changedMeasurements) {
        if (state.activeSectorIndex < 0) return;
        for (TargetRef target : state.visitTargets) {
            SourceLink link = state.detections.get(target);
            SectorConfirmation confirmation = link == null ? null : link.sectors.get(state.activeSectorIndex);
            if (confirmation != null) {
                freezeConfirmation(confirmation, tick, state.fadeDurationTicks, changedMeasurements, network.id);
            }
        }
    }

    private void freezeConfirmation(SectorConfirmation confirmation, long tick, long fadeTicks,
                                    Set<NetworkId> changedMeasurements, NetworkId networkId) {
        LiveResult current = confirmation.measurement;
        if (current == null || !current.observing()) return;
        confirmation.measurement = current.frozen(tick, fadeTicks);
        changedMeasurements.add(networkId);
    }

    private void removeSectorConfirmation(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                          TargetRef target, int sectorIndex,
                                          Set<NetworkId> changedDiscoveries) {
        SourceLink link = state.detections.get(target);
        if (link == null || link.sectors.remove(sectorIndex) == null) return;
        if (!link.sectors.isEmpty()) return;
        state.detections.remove(target);
        TargetEntry entry = network.targets.get(target);
        if (entry != null) {
            entry.sources.remove(endpoint, link);
            if (entry.sources.isEmpty()) network.targets.remove(target, entry);
        }
        changedDiscoveries.add(network.id);
    }

    private void logObservationSummary(NetworkRuntime network, EndpointKey endpoint, RadarState state, long tick) {
        RadarObservationPlan plan = state.plan;
        RadarDiagnostics.log("observation_window_summary", network.id, endpoint, state.slotSerial + 1, tick,
                "slot=" + state.slotSerial + " sector=" + state.activeSectorIndex + " variant="
                        + (plan == null ? 0 : plan.sectorOrderVariant()) + " configRevision="
                        + (plan == null ? -1 : plan.configRevision()) + " geometryRevision="
                        + (plan == null ? -1 : plan.geometryRevision()) + " outcome=" + state.searchOutcome
                        + " spatialQueries=" + state.spatialQueries + " candidates=" + state.candidates
                        + " acceptedHitboxes=" + state.acceptedHitboxes + " found=" + state.foundTargets
                        + " knownChecks=" + state.knownChecks + " observing=" + state.visitTargets.size()
                        + " uuidLookups=" + state.uuidLookups + " cacheHits=" + state.cacheHits
                        + " searchNanos=" + state.searchDurationNanos + " checkNanos=" + state.checkDurationNanos);
    }

    private static void resetWindowMetrics(RadarState state) {
        state.searchOutcome = "not_started";
        state.spatialQueries = 0;
        state.candidates = 0;
        state.acceptedHitboxes = 0;
        state.foundTargets = 0;
        state.knownChecks = 0;
        state.searchDurationNanos = 0L;
        state.checkDurationNanos = 0L;
        state.uuidLookups = 0L;
        state.cacheHits = 0L;
    }

    private void ensureCompatiblePolicy(EndpointKey endpoint, NetworkId networkId, NetworkPolicy policy) {
        NetworkRuntime network = networks.get(networkId);
        if (network != null && network.policy != null && !network.policy.equals(policy)) {
            throw new IllegalArgumentException("Radar policy conflict for network " + networkId
                    + ": active=" + network.policy + " requested=" + policy + " endpoint=" + endpoint);
        }
    }

    private void removePreviousRegistration(EndpointKey endpoint, Set<NetworkId> changedNetworks) {
        Registration previous = endpointIndex.remove(endpoint);
        if (previous == null) return;
        removeNetworkEndpoint(previous.networkId, endpoint);
        removeRegistrationData(endpoint, previous, changedNetworks);
    }

    private void removeRegistrationData(EndpointKey endpoint, Registration registration,
                                        Set<NetworkId> changedNetworks) {
        NetworkRuntime network = networks.get(registration.networkId);
        if (network != null && registration.role == EndpointRole.RADAR) {
            RadarState state = network.radars.remove(endpoint);
            if (state != null) {
                if (state.activeSectorIndex >= 0) logObservationSummary(network, endpoint, state, serverTick);
                for (TargetRef target : List.copyOf(state.detections.keySet())) {
                    SourceLink link = state.detections.get(target);
                    TargetEntry entry = network.targets.get(target);
                    if (entry != null) {
                        entry.sources.remove(endpoint, link);
                        if (entry.sources.isEmpty()) network.targets.remove(target, entry);
                    }
                }
                changedNetworks.add(network.id);
            }
        }
        removeNetworkIfUnused(registration.networkId, network);
    }

    private void addNetworkEndpoint(NetworkId networkId, EndpointKey endpoint) {
        endpointsByNetwork.computeIfAbsent(networkId, ignored -> new HashSet<>()).add(endpoint);
    }

    private void removeNetworkEndpoint(NetworkId networkId, EndpointKey endpoint) {
        Set<EndpointKey> endpoints = endpointsByNetwork.get(networkId);
        if (endpoints == null) return;
        endpoints.remove(endpoint);
        if (endpoints.isEmpty()) endpointsByNetwork.remove(networkId);
    }

    private void removeNetworkIfUnused(NetworkId id, NetworkRuntime network) {
        if (network != null && !endpointsByNetwork.containsKey(id)) networks.remove(id, network);
    }

    private void markChanged(Set<NetworkId> changedNetworks, boolean measurement) {
        for (NetworkId id : changedNetworks) {
            NetworkRuntime network = networks.get(id);
            if (network != null) {
                network.revision++;
                if (measurement) {
                    network.measurementRevision++;
                }
            }
        }
    }

    private Registration matchingRegistration(EndpointKey endpoint, NetworkId networkId,
                                               RegistrationToken token, EndpointRole role) {
        Registration registration = endpointIndex.get(endpoint);
        if (registration == null || !registration.networkId.equals(networkId) || !registration.token.equals(token)
                || role != null && registration.role != role) return null;
        return registration;
    }

    private Registration queryRegistration(EndpointKey endpoint, NetworkId networkId,
                                            RegistrationToken token, String operation) {
        Registration registration = matchingRegistration(endpoint, networkId, token, null);
        if (registration == null || !registration.role.canQueryNetwork()) {
            if (RadarDiagnostics.queryDiagnosticsEnabled()) {
                RadarDiagnostics.log("consumer_access_rejected", networkId, endpoint, null, serverTick,
                        "operation=" + operation + " reason=registration_token_mismatch");
            }
            return null;
        }
        return registration;
    }

    private int activeRadarCount() {
        return networks.values().stream().mapToInt(network -> network.radars.size()).sum();
    }

    private void checkActive() {
        if (closed) throw new IllegalStateException("Radar network manager is closed");
        if (!server.isSameThread()) throw new IllegalStateException("Radar network access must run on server thread");
    }

    private void close() {
        if (closed) return;
        closed = true;
        endpointIndex.clear();
        endpointsByNetwork.clear();
        networks.clear();
    }

    /** Положительные и отрицательные UUID-разрешения действуют только внутри одного ServerTickEvent.Pre. */
    private final class PhaseEntityCache {
        private final Map<TargetRef, Optional<Entity>> entities = new HashMap<>();
        private long uuidLookups;
        private long cacheHits;

        void remember(TargetRef target, Entity entity) {
            entities.putIfAbsent(target, Optional.of(entity));
        }

        Entity resolve(TargetRef target) {
            Optional<Entity> cached = entities.get(target);
            if (cached != null) {
                cacheHits++;
                return cached.orElse(null);
            }
            uuidLookups++;
            Entity entity = null;
            if (target.sourceKind() == TargetRef.SourceKind.ENTITY) {
                ServerLevel level = server.getLevel(target.dimension());
                if (level != null) entity = level.getEntity(target.id());
            }
            entities.put(target, Optional.ofNullable(entity));
            return entity;
        }

        void clear() { entities.clear(); }
    }

    /** Фаза одного радара для визуального профиля; клиент получает её как данные, не выводит из пакетного времени. */
    public record RadarVisualWindow(com.electroradar.radar.visual.RadarVisualProfile profile,
                                    long geometryRevision, long slotSerial, int activeSectorIndex,
                                    long startTick, long endTick, int sectorOrderVariant) {
        public RadarVisualWindow {
            Objects.requireNonNull(profile, "profile");
            if (geometryRevision < 0 || slotSerial < 0 || activeSectorIndex < 0
                    || activeSectorIndex >= profile.sectorCount()
                    || startTick < 0 || endTick <= startTick || !RadarSectorOrder.validVariant(sectorOrderVariant)) {
                throw new IllegalArgumentException("Invalid radar visual window");
            }
        }
    }
}
