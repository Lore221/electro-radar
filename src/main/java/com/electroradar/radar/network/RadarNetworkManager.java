package com.electroradar.radar.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
import com.electroradar.radar.network.RadarNetworkState.VisitWindow;
import com.electroradar.radar.network.RadarNetworkState.VisitPhase;
import com.electroradar.radar.network.RadarNetworkState.ZoneVisit;
import com.electroradar.radar.scan.EntityRadarScanner;

/**
 * Серверный координатор секторного наблюдения и владелец регистраций, обнаружений и памяти.
 *
 * <p>Всё выполняется на серверном потоке в ServerTickEvent.Pre, до тиков BlockEntity, поэтому
 * потребители в том же тике читают уже завершённый результат.</p>
 *
 * <p><b>Окна.</b> У каждой сети своё окно длиной D тиков. Внутри сети все радары меняют
 * сектор и ищут цели синхронно. Первое окно сети начинается в тик, где
 * {@code (tick - offset) mod D == 0}, где {@code offset} выводится из NetworkId. Поэтому разные
 * сети, даже загруженные одновременно, ищут цели в разные тики, а не одним пиком.</p>
 *
 * <p><b>Посещение.</b> Для источника каждое окно — это одно посещение сектора с явной фазой
 * {@link VisitPhase}: IDLE → (окно началось) → OBSERVING или SKIPPED → (следующее окно) → ...
 * В начале OBSERVING выполняется ровно один пространственный поиск, затем до конца окна
 * каждый тик проверяется только найденный набор UUID.</p>
 *
 * <p><b>Версия.</b> У сети один счётчик {@code contentVersion} — версия видимого содержимого
 * (гарантии и их границы описаны в {@link TargetSnapshot}). Он берётся из общей для менеджера
 * монотонной последовательности, поэтому номер никогда не повторяется, даже если runtime сети
 * удалили и создали заново.</p>
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
    private long versionSequence;
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
        RadarDiagnostics.log("network_manager_started", null, null, null, 0L, "phase=per_network_pre_tick");
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

    /** Регистрирует источник; он начинает участвовать со следующего окна своей сети. */
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
        long participatesFromSlot = network.windowStarted() ? network.slotSerial + 1 : 0;
        endpointIndex.put(endpoint, new Registration(networkId, EndpointRole.RADAR, token));
        addNetworkEndpoint(networkId, endpoint);
        network.radars.put(endpoint, new RadarState(token, participatesFromSlot, source));
        changed.add(networkId);
        markChanged(changed);
        RadarDiagnostics.log("radar_registered", networkId, endpoint, null, serverTick,
                () -> "token=" + token.value() + " participatesFromSlot=" + participatesFromSlot
                        + " nextBoundary=" + (network.windowStarted() ? network.windowEndTick : "aligned_start"));
        return token;
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
        markChanged(changed);
        RadarDiagnostics.log("consumer_registered", networkId, null, null, serverTick,
                () -> "endpoint=" + endpoint + " role=" + role + " token=" + token.value());
        return token;
    }

    public List<EndpointKey> registeredEndpoints(NetworkId networkId) {
        checkActive();
        Set<EndpointKey> endpoints = endpointsByNetwork.get(Objects.requireNonNull(networkId, "networkId"));
        return endpoints == null ? List.of() : endpoints.stream().sorted(RadarNetworkState.ENDPOINT_ORDER).toList();
    }

    /** Текущее посещение источника для отображения; пусто, если источник сейчас не наблюдает. */
    public Optional<RadarVisualWindow> visualWindowForRadar(EndpointKey radar) {
        checkActive();
        Registration registration = endpointIndex.get(radar);
        NetworkRuntime network = registration == null ? null : networks.get(registration.networkId);
        RadarState state = network == null ? null : network.radars.get(radar);
        if (registration == null || registration.role != EndpointRole.RADAR || state == null
                || !state.token.equals(registration.token) || state.plan == null
                || !state.observing() || state.visitWindow == null) {
            return Optional.empty();
        }
        VisitWindow window = state.visitWindow;
        List<Integer> active = window.activeSectorIndices().stream()
                .filter(index -> {
                    ZoneVisit zone = state.zoneVisits.get(index);
                    return zone != null && zone.observing();
                }).toList();
        if (active.isEmpty()) return Optional.empty();
        return Optional.of(new RadarVisualWindow(state.plan.profile(), active,
                window.startTick(), window.endTick()));
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
        markChanged(changed);
        RadarDiagnostics.log(registration.role == EndpointRole.RADAR ? "radar_unregistered" : "consumer_unregistered",
                networkId, registration.role == EndpointRole.RADAR ? endpoint : null, null, serverTick,
                () -> "endpoint=" + endpoint + " token=" + token.value());
        return true;
    }

    /**
     * Версия видимого состояния сети для этой регистрации; {@code -1}, если регистрация
     * неактуальна. Дешёвая проверка: потребитель перечитывает цели, только когда она изменилась.
     */
    public long contentVersion(EndpointKey consumer, NetworkId networkId, RegistrationToken token) {
        checkActive();
        Registration registration = matchingRegistration(consumer, networkId, token, null);
        if (registration == null || !registration.role.canQueryNetwork()) return -1L;
        NetworkRuntime network = networks.get(networkId);
        return network == null ? 0L : network.contentVersion;
    }

    public <T> TargetSnapshot<T> queryTargets(EndpointKey consumer, NetworkId networkId,
                                              RegistrationToken token, TargetProjection<T> projection) {
        checkActive();
        Objects.requireNonNull(projection, "projection");
        Registration registration = queryRegistration(consumer, networkId, token, "query_targets");
        if (registration == null) return TargetSnapshot.empty();
        return RadarNetworkQueries.queryTargets(consumer, registration, projection, serverTick,
                endpointIndex, networks);
    }

    public TargetSnapshot<TargetRef> queryTargetRefs(EndpointKey consumer, NetworkId networkId,
                                                      RegistrationToken token) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "query_target_refs");
        if (registration == null) return TargetSnapshot.empty();
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
        if (registration == null) return TargetSnapshot.empty();
        return RadarNetworkQueries.queryTargetDetails(consumer, registration, targets, projection, serverTick,
                endpointIndex, networks);
    }

    public TargetSources sourcesForTarget(EndpointKey consumer, NetworkId networkId,
                                         RegistrationToken token, TargetRef target) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "sources_for_target");
        if (registration == null) return new TargetSources(target, 0, List.of(), Optional.empty());
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
        markChanged(Set.of(networkId));
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

    // ---------------------------------------------------------------------------------------
    // Тик: окна сетей и посещения источников
    // ---------------------------------------------------------------------------------------

    private void beginServerTick() {
        checkActive();
        serverTick = Math.incrementExact(serverTick);
        RadarServerConfig.Snapshot parameters = RadarServerConfig.snapshot();
        Set<NetworkId> changed = new HashSet<>();
        PhaseEntityCache entityCache = new PhaseEntityCache();
        try {
            for (NetworkRuntime network : networks.values()) {
                if (network.radars.isEmpty()) {
                    resetWindow(network);
                    continue;
                }
                boolean boundary = advanceWindow(network, parameters.sectorTicks());
                if (!network.windowStarted()) continue;
                if (boundary) {
                    // Смена окна меняет активный сектор каждого радара, а это видно потребителю.
                    changed.add(network.id);
                    logBoundary(network, parameters);
                }
                ObservationWindowSchedule.NetworkWindow window = new ObservationWindowSchedule.NetworkWindow(
                        network.slotSerial, network.windowStartTick, network.windowEndTick);
                for (Map.Entry<EndpointKey, RadarState> radarEntry : network.radars.entrySet()) {
                    EndpointKey endpoint = radarEntry.getKey();
                    RadarState state = radarEntry.getValue();
                    try {
                        processRadar(network, endpoint, state, parameters, boundary, window, entityCache, changed);
                    } catch (RuntimeException exception) {
                        endVisit(network, endpoint, state, changed);
                        becomeIdle(state);
                        RadarDiagnostics.logException("observation_source_failed", network.id, endpoint,
                                network.slotSerial, serverTick, "active_rights=revoked other_sources=continue",
                                exception);
                    }
                }
            }
        } finally {
            entityCache.clear();
        }
        markChanged(changed);
    }

    /**
     * Продвигает окно сети. Первое окно выравнивается по сдвигу из NetworkId, следующие
     * начинаются ровно на границе предыдущего. Новый D из конфига действует с новой границы.
     *
     * @return true, если в этот тик началось новое окно
     */
    private boolean advanceWindow(NetworkRuntime network, long durationTicks) {
        if (!network.windowStarted()) {
            long offset = Math.floorMod(network.id.hashCode(), durationTicks);
            if (Math.floorMod(serverTick - offset, durationTicks) != 0) return false;
            network.slotSerial = 0;
        } else if (serverTick < network.windowEndTick) {
            return false;
        } else {
            network.slotSerial = Math.incrementExact(network.slotSerial);
        }
        network.windowStartTick = serverTick;
        network.windowEndTick = Math.addExact(serverTick, durationTicks);
        return true;
    }

    /** Сеть без радаров теряет окно; следующий радар начнёт его с выравнивания заново. */
    private static void resetWindow(NetworkRuntime network) {
        network.slotSerial = -1;
        network.windowStartTick = -1;
        network.windowEndTick = -1;
    }

    /**
     * Один тик одного источника. Готовность общая для физического источника, а поиск, начальный
     * набор UUID, фаза и ошибки изолированы по каждой активной геометрической зоне.
     */
    private void processRadar(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                              RadarServerConfig.Snapshot parameters, boolean boundary,
                              ObservationWindowSchedule.NetworkWindow networkWindow,
                              PhaseEntityCache cache, Set<NetworkId> changed) {
        if (state.participatesFromSlot > network.slotSerial) return;
        if (boundary && !refreshPlan(network, endpoint, state, parameters, changed)) return;
        if (state.plan == null) return;

        ObservationWindowSchedule.ObservationWindow window = scheduledWindow(network, endpoint, state,
                networkWindow, changed);
        if (window == null) return;

        boolean newVisit = state.visitWindow == null
                || !state.visitWindow.sameWindow(window, state.plan.geometryRevision());
        if (newVisit) {
            endVisit(network, endpoint, state, changed);
            state.visitWindow = new VisitWindow(window.activeSectorIndices(), window.startTick(),
                    window.endTick(), window.fadeDurationTicks(), state.plan.geometryRevision());
            for (int sectorIndex : window.activeSectorIndices()) {
                ZoneVisit zone = new ZoneVisit(sectorIndex, state.visitWindow);
                zone.phase = VisitPhase.SKIPPED;
                state.zoneVisits.put(sectorIndex, zone);
            }
            if (window.startTick() != serverTick) {
                // Поиск разрешён только в первый тик посещения; опоздавший источник ждёт.
                for (ZoneVisit zone : state.zoneVisits.values()) zone.metrics.outcome = "window_start_missed";
                RadarDiagnostics.log("observation_window_skipped", network.id, endpoint, network.slotSerial,
                        serverTick, () -> "reason=window_start_missed startTick=" + window.startTick()
                                + " sectors=" + window.activeSectorIndices());
                return;
            }
        } else if (!state.observing()) {
            // Посещение уже пропущено: повторный поиск в том же окне запрещён.
            return;
        }

        if (!isReady(network, endpoint, state)) {
            if (state.observing()) {
                RadarDiagnostics.log("observation_revoked", network.id, endpoint, network.slotSerial,
                        serverTick, "reason=source_not_ready");
            }
            for (ZoneVisit zone : state.zoneVisits.values()) zone.metrics.outcome = "source_not_ready";
            endVisit(network, endpoint, state, changed);
            return;
        }

        if (newVisit) {
            for (int sectorIndex : window.activeSectorIndices()) {
                ZoneVisit zone = state.zoneVisits.get(sectorIndex);
                zone.phase = VisitPhase.OBSERVING;
                changed.add(network.id);
                try {
                    searchSector(network, endpoint, state, zone, cache, changed);
                } catch (RuntimeException exception) {
                    zone.metrics.outcome = "search_exception";
                    endZoneVisit(network, endpoint, state, zone, changed);
                    RadarDiagnostics.logException("observation_search_failed", network.id, endpoint,
                            network.slotSerial, serverTick, "sector=" + sectorIndex
                                    + " retry=next_visit active_rights=revoked other_zones=continue", exception);
                }
            }
        }
        for (int sectorIndex : window.activeSectorIndices()) {
            ZoneVisit zone = state.zoneVisits.get(sectorIndex);
            if (zone == null || !zone.observing()) continue;
            try {
                trackKnownTargets(network, endpoint, state, zone, cache, changed);
            } catch (RuntimeException exception) {
                zone.metrics.outcome = "known_target_check_failed";
                endZoneVisit(network, endpoint, state, zone, changed);
                RadarDiagnostics.logException("known_zone_check_failed", network.id, endpoint,
                        network.slotSerial, serverTick, "sector=" + sectorIndex
                                + " active_rights=revoked other_zones=continue", exception);
            }
        }
    }

    /**
     * На границе окна пересобирает план источника.
     *
     * <p><b>Память без плана.</b> Пустой план означает любую неготовность: апертуру разобрали,
     * контроллер повернули или блок временно не готов. Различать эти причины ядро не берётся,
     * поэтому политика одна: источник перестаёт наблюдать, текущие цели замораживаются, а
     * память живёт до конца своего затухания и снимается на первой границе после него.
     * Так временная неготовность не стирает картину мгновенно, затухание не обрывается раньше
     * срока, и бессрочной памяти у выключенного радара тоже нет. Исключение при сборке плана
     * памяти не трогает: это сбой, а не выключение.</p>
     *
     * @return true, если план есть и обработку тика нужно продолжить
     */
    private boolean refreshPlan(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                RadarServerConfig.Snapshot parameters, Set<NetworkId> changed) {
        Optional<RadarObservationPlan> prepared;
        try {
            prepared = state.source.createObservationPlan(parameters);
        } catch (RuntimeException exception) {
            endVisit(network, endpoint, state, changed);
            becomeIdle(state);
            RadarDiagnostics.logException("observation_plan_failed", network.id, endpoint,
                    network.slotSerial, serverTick, "retry=next_network_boundary", exception);
            return false;
        }
        if (prepared.isEmpty()) {
            boolean hadPlan = state.plan != null;
            endVisit(network, endpoint, state, changed);
            becomeIdle(state);
            int forgotten = forgetFadedConfirmations(network, endpoint, state, changed);
            if (hadPlan || forgotten > 0) {
                RadarDiagnostics.log("observation_plan_unavailable", network.id, endpoint, network.slotSerial,
                        serverTick, () -> "action=keep_until_faded forgotten=" + forgotten
                                + " remaining=" + state.detections.size());
            }
            return false;
        }
        state.plan = prepared.orElseThrow();
        return true;
    }

    /** Окно источника на этот тик; {@code null} — пауза или ошибка расписания (посещение закрыто). */
    private ObservationWindowSchedule.ObservationWindow scheduledWindow(
            NetworkRuntime network, EndpointKey endpoint, RadarState state,
            ObservationWindowSchedule.NetworkWindow networkWindow, Set<NetworkId> changed) {
        try {
            Optional<ObservationWindowSchedule.ObservationWindow> next =
                    state.plan.schedule().windowAt(serverTick, networkWindow);
            if (next.isEmpty()) {
                endVisit(network, endpoint, state, changed);
                state.visitWindow = null;
                return null;
            }
            ObservationWindowSchedule.ObservationWindow window = next.orElseThrow();
            if (!state.plan.dimension().equals(endpoint.dimension().location())
                    || window.activeSectorIndices().stream()
                            .anyMatch(index -> index >= state.plan.sectors().size())
                    || window.startTick() > serverTick || window.endTick() <= serverTick) {
                throw new IllegalStateException("Source schedule returned a window invalid for this tick");
            }
            return window;
        } catch (RuntimeException exception) {
            endVisit(network, endpoint, state, changed);
            state.visitWindow = null;
            RadarDiagnostics.logException("observation_schedule_failed", network.id, endpoint,
                    network.slotSerial, serverTick, "active_rights=revoked retry=next_schedule_window", exception);
            return null;
        }
    }

    private boolean isReady(NetworkRuntime network, EndpointKey endpoint, RadarState state) {
        try {
            return state.source.isReadyForObservation(state.plan);
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("observation_readiness_failed", network.id, endpoint,
                    network.slotSerial, serverTick, "active_rights=revoked", exception);
            return false;
        }
    }

    /** Один запрос на входе в зону; ошибка не становится отрицательным результатом поиска. */
    private void searchSector(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                              ZoneVisit zone,
                              PhaseEntityCache cache, Set<NetworkId> changed) {
        RadarObservationPlan.Sector sector = state.plan.sectors().get(zone.sectorIndex);
        if (sector.area().isEmpty()) {
            zone.metrics.outcome = "empty_geometry";
            applySuccessfulSearch(network, endpoint, state, zone, Map.of(), changed);
            return;
        }
        ServerLevel level = server.getLevel(endpoint.dimension());
        if (level == null) {
            // Посещение остаётся OBSERVING с пустым набором: это не отрицательный поиск.
            zone.metrics.outcome = "dimension_unloaded";
            RadarDiagnostics.log("observation_search_unavailable", network.id, endpoint, network.slotSerial,
                    serverTick, "reason=dimension_unloaded sector=" + zone.sectorIndex);
            return;
        }
        long searchStarted = System.nanoTime();
        zone.metrics.spatialQueries++;
        EntityRadarScanner.EntitySearchResult result = SCANNER.scanEntities(
                level, sector.area(), state.plan.eligibility());
        zone.metrics.searchDurationNanos += System.nanoTime() - searchStarted;
        zone.metrics.outcome = "search_succeeded";
        zone.metrics.candidates += result.candidateCount();
        zone.metrics.acceptedHitboxes += result.acceptedHitboxCount();
        zone.metrics.foundTargets += result.entities().size();
        for (Map.Entry<TargetRef, Entity> found : result.entities().entrySet()) {
            cache.remember(found.getKey(), found.getValue());
        }
        applySuccessfulSearch(network, endpoint, state, zone, result.entities(), changed);
    }

    /**
     * Записывает найденные цели в начальный набор и считает промахи. Промах получает только
     * подтверждение того же геометрического сектора; на {@code forgetAfterMisses}-м оно снимается.
     */
    private void applySuccessfulSearch(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                       ZoneVisit zone,
                                       Map<TargetRef, Entity> found, Set<NetworkId> changed) {
        int sectorIndex = zone.sectorIndex;
        zone.targets.clear();
        for (TargetRef target : found.keySet()) {
            if (!target.dimension().equals(endpoint.dimension())) continue;
            SourceLink link = state.detections.computeIfAbsent(target, ignored -> {
                TargetEntry entry = network.targets.computeIfAbsent(target, unused -> new TargetEntry());
                SourceLink created = new SourceLink();
                entry.sources.put(endpoint, created);
                changed.add(network.id);
                return created;
            });
            SectorConfirmation confirmation = link.sectors.computeIfAbsent(sectorIndex, ignored -> {
                changed.add(network.id);
                return new SectorConfirmation();
            });
            confirmation.missedVisits = 0;
            confirmation.lastSuccessfulVisitTick = serverTick;
            zone.targets.add(target);
        }
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
            removeSectorConfirmation(network, endpoint, state, target, sectorIndex, changed);
            RadarDiagnostics.log("sector_confirmation_forgotten", network.id, endpoint, network.slotSerial,
                    serverTick, () -> "target=" + target + " sector=" + sectorIndex + " reason=miss_limit");
        }
    }

    /**
     * Обновляет измерения начального набора. Цель вне сектора замораживается, вернувшаяся до
     * конца окна снова наблюдается без нового поиска. Версия сети меняется только при видимом
     * изменении (положение, тип, переход наблюдение/память), а не каждый тик неподвижной цели.
     */
    private void trackKnownTargets(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                   ZoneVisit zone,
                                   PhaseEntityCache cache, Set<NetworkId> changed) {
        int sectorIndex = zone.sectorIndex;
        RadarObservationPlan.Sector sector = state.plan.sectors().get(sectorIndex);
        if (sector.area().isEmpty()) return;
        long startedAt = System.nanoTime();
        long lookupsBefore = cache.uuidLookups;
        long hitsBefore = cache.cacheHits;
        for (TargetRef target : zone.targets) {
            zone.metrics.knownChecks++;
            SourceLink link = state.detections.get(target);
            SectorConfirmation confirmation = link == null ? null : link.sectors.get(sectorIndex);
            if (confirmation == null) continue;
            try {
                Entity entity = cache.resolve(target);
                if (entity == null || !EntityRadarScanner.anyHitboxInside(entity, sector.area())) {
                    freezeConfirmation(confirmation, zone.window.fadeDurationTicks(), changed, network.id);
                    continue;
                }
                LiveResult next = LiveResult.observed(serverTick, entity.position(), entity.getType());
                if (!next.sameVisibleState(confirmation.measurement)) changed.add(network.id);
                confirmation.measurement = next;
                confirmation.lastSuccessfulVisitTick = serverTick;
            } catch (RuntimeException exception) {
                freezeConfirmation(confirmation, zone.window.fadeDurationTicks(), changed, network.id);
                RadarDiagnostics.logException("known_target_check_failed", network.id, endpoint,
                        network.slotSerial, serverTick, "target=" + target + " sector=" + sectorIndex, exception);
            }
        }
        zone.metrics.uuidLookups += cache.uuidLookups - lookupsBefore;
        zone.metrics.cacheHits += cache.cacheHits - hitsBefore;
        zone.metrics.checkDurationNanos += System.nanoTime() - startedAt;
    }

    /** Закрывает все активные зоны источника независимо друг от друга. */
    private void endVisit(NetworkRuntime network, EndpointKey endpoint, RadarState state, Set<NetworkId> changed) {
        for (ZoneVisit zone : state.zoneVisits.values()) {
            endZoneVisit(network, endpoint, state, zone, changed);
        }
        state.zoneVisits.clear();
    }

    /** Ошибка или конец окна отзывает только эту зону и сохраняет её собственное затухание. */
    private void endZoneVisit(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                              ZoneVisit zone, Set<NetworkId> changed) {
        if (!zone.observing()) return;
        logVisitSummary(network, endpoint, zone, state.plan);
        for (TargetRef target : zone.targets) {
            SourceLink link = state.detections.get(target);
            SectorConfirmation confirmation = link == null ? null : link.sectors.get(zone.sectorIndex);
            if (confirmation != null) {
                freezeConfirmation(confirmation, zone.window.fadeDurationTicks(), changed, network.id);
            }
        }
        zone.targets.clear();
        zone.phase = VisitPhase.SKIPPED;
        // Эта зона пропадает с монитора; остальные зоны источника могут продолжить работу.
        changed.add(network.id);
    }

    private static void becomeIdle(RadarState state) {
        state.plan = null;
        state.visitWindow = null;
        state.zoneVisits.clear();
    }

    private void freezeConfirmation(SectorConfirmation confirmation, long fadeTicks,
                                    Set<NetworkId> changed, NetworkId networkId) {
        LiveResult current = confirmation.measurement;
        if (current == null || !current.observing()) return;
        confirmation.measurement = current.frozen(serverTick, fadeTicks);
        changed.add(networkId);
    }

    private void removeSectorConfirmation(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                          TargetRef target, int sectorIndex, Set<NetworkId> changed) {
        SourceLink link = state.detections.get(target);
        if (link == null || link.sectors.remove(sectorIndex) == null) return;
        if (!link.sectors.isEmpty()) return;
        state.detections.remove(target);
        removeTargetSource(network, endpoint, target, link);
        changed.add(network.id);
    }

    /**
     * Снимает подтверждения, у которых закончилось затухание (или которые так и не получили
     * измерения). Наблюдаемых здесь нет: источник без плана уже заморозил свой набор.
     */
    private int forgetFadedConfirmations(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                         Set<NetworkId> changed) {
        int forgotten = 0;
        java.util.Iterator<Map.Entry<TargetRef, SourceLink>> detections = state.detections.entrySet().iterator();
        while (detections.hasNext()) {
            Map.Entry<TargetRef, SourceLink> detection = detections.next();
            SourceLink link = detection.getValue();
            int before = link.sectors.size();
            link.sectors.values().removeIf(this::faded);
            forgotten += before - link.sectors.size();
            if (link.sectors.isEmpty()) {
                detections.remove();
                removeTargetSource(network, endpoint, detection.getKey(), link);
            }
        }
        if (forgotten > 0) changed.add(network.id);
        return forgotten;
    }

    private boolean faded(SectorConfirmation confirmation) {
        LiveResult measurement = confirmation.measurement;
        if (measurement == null) return true;
        if (measurement.observing() || measurement.freezeTick() < 0) return false;
        return serverTick - measurement.freezeTick() >= measurement.fadeDurationTicks();
    }

    /** Снимает все подтверждения источника с обеих сторон индекса. */
    private int forgetAllConfirmations(NetworkRuntime network, EndpointKey endpoint, RadarState state,
                                       Set<NetworkId> changed) {
        int count = state.detections.size();
        for (Map.Entry<TargetRef, SourceLink> detection : state.detections.entrySet()) {
            removeTargetSource(network, endpoint, detection.getKey(), detection.getValue());
        }
        state.detections.clear();
        if (count > 0) changed.add(network.id);
        return count;
    }

    private static void removeTargetSource(NetworkRuntime network, EndpointKey endpoint,
                                           TargetRef target, SourceLink link) {
        TargetEntry entry = network.targets.get(target);
        if (entry == null) return;
        entry.sources.remove(endpoint, link);
        if (entry.sources.isEmpty()) network.targets.remove(target, entry);
    }

    // ---------------------------------------------------------------------------------------
    // Регистрации и версии
    // ---------------------------------------------------------------------------------------

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
                for (ZoneVisit zone : state.zoneVisits.values()) {
                    if (zone.observing()) logVisitSummary(network, endpoint, zone, state.plan);
                }
                forgetAllConfirmations(network, endpoint, state, changedNetworks);
                changedNetworks.add(network.id);
            }
        }
        // Потребители тоже меняют доступность, поэтому сеть получает новую версию в любом случае.
        changedNetworks.add(registration.networkId);
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

    /** Назначает изменённым сетям новый номер из общей монотонной последовательности. */
    private void markChanged(Set<NetworkId> changedNetworks) {
        for (NetworkId id : changedNetworks) {
            NetworkRuntime network = networks.get(id);
            if (network != null) network.contentVersion = ++versionSequence;
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

    // ---------------------------------------------------------------------------------------
    // Диагностика и жизненный цикл
    // ---------------------------------------------------------------------------------------

    private void logBoundary(NetworkRuntime network, RadarServerConfig.Snapshot parameters) {
        RadarDiagnostics.log("observation_network_boundary", network.id, null, network.slotSerial, serverTick,
                () -> "window=[" + network.windowStartTick + "," + network.windowEndTick + ")"
                        + " configRevision=" + parameters.revision() + " sources=" + network.radars.size());
    }

    private void logVisitSummary(NetworkRuntime network, EndpointKey endpoint, ZoneVisit zone,
                                RadarObservationPlan plan) {
        RadarDiagnostics.log("observation_visit_summary", network.id, endpoint, network.slotSerial, serverTick, () -> {
            RadarNetworkState.VisitMetrics m = zone.metrics;
            return "sector=" + zone.sectorIndex
                    + " variant=" + (plan == null ? 0 : plan.sectorOrderVariant())
                    + " geometryRevision=" + (zone.window == null ? -1 : zone.window.geometryRevision())
                    + " outcome=" + m.outcome + " spatialQueries=" + m.spatialQueries
                    + " candidates=" + m.candidates + " acceptedHitboxes=" + m.acceptedHitboxes
                    + " found=" + m.foundTargets + " knownChecks=" + m.knownChecks
                    + " observing=" + zone.targets.size() + " uuidLookups=" + m.uuidLookups
                    + " cacheHits=" + m.cacheHits + " searchNanos=" + m.searchDurationNanos
                    + " checkNanos=" + m.checkDurationNanos;
        });
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

    /** Текущее окно радара и успешные активные зоны для одного визуального профиля. */
    public record RadarVisualWindow(com.electroradar.radar.visual.RadarVisualProfile profile,
                                    List<Integer> activeSectorIndices, long startTick, long endTick) {
        public RadarVisualWindow {
            Objects.requireNonNull(profile, "profile");
            activeSectorIndices = List.copyOf(activeSectorIndices);
            if (activeSectorIndices.isEmpty() || activeSectorIndices.size() > profile.sectorCount()
                    || activeSectorIndices.stream().anyMatch(index -> index < 0 || index >= profile.sectorCount())
                    || new HashSet<>(activeSectorIndices).size() != activeSectorIndices.size()
                    || startTick < 0 || endTick <= startTick) {
                throw new IllegalArgumentException("Invalid radar visual window");
            }
        }

        public RadarVisualWindow(com.electroradar.radar.visual.RadarVisualProfile profile,
                                 int activeSectorIndex, long startTick, long endTick) {
            this(profile, List.of(activeSectorIndex), startTick, endTick);
        }
    }
}
