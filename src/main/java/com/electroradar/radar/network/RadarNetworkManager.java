package com.electroradar.radar.network;

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

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.CompletedScan;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.RadarState;
import com.electroradar.radar.network.RadarNetworkState.Registration;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/**
 * Серверное ядро сетей, создаваемое отдельно для каждого MinecraftServer.
 *
 * <p>Менеджер — единственный владелец runtime-регистраций и состояния сетей, включая ожидающие
 * отчёты, связи «радар–цель» и опубликованные live-значения. Он координирует расписание окон;
 * {@link RadarNetworkPublisher} синхронно применяет закрытое окно внутри этого перехода.
 * Ни один компонент не сохраняет Entity: UUID разрешается в координаты и тип при публикации.</p>
 *
 * <p>На границе сначала завершается переход всех таблиц, затем записывается один полный
 * снимок опубликованной ревизии. Диагностика состояния расположена рядом с его изменением.</p>
 */
public final class RadarNetworkManager {
    private static final Map<MinecraftServer, RadarNetworkManager> MANAGERS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final MinecraftServer server;
    /** Публикация владеет согласованным изменением связей и LiveResult, но не расписанием окон. */
    private final RadarNetworkPublisher publisher;
    private final Map<EndpointKey, Registration> endpointIndex = new HashMap<>();
    private final Map<NetworkId, NetworkRuntime> networks = new HashMap<>();
    private final NavigableMap<Long, Set<NetworkId>> scheduledBoundaries = new TreeMap<>();
    private long serverTick;
    private long revisionSequence;
    private boolean closed;

    /** Создаёт runtime-менеджер, принадлежащий одному серверу. */
    private RadarNetworkManager(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.publisher = new RadarNetworkPublisher(server);
    }

    /**
     * Возвращает активный менеджер заданного сервера.
     * @param server сервер-владелец менеджера
     * @return менеджер, созданный при запуске этого сервера
     * @throws IllegalStateException если вызов сделан не с серверного потока или менеджер не активен
     */
    public static RadarNetworkManager forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Radar network access must run on the server thread");
        }
        synchronized (MANAGERS) {
            RadarNetworkManager manager = MANAGERS.get(server);
            if (manager == null || manager.closed) {
                throw new IllegalStateException("Radar network manager is not active for this server");
            }
            return manager;
        }
    }

    /** Создаёт пустой менеджер в начале жизни сервера и закрывает оставшийся экземпляр при повторном запуске. */
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        RadarNetworkManager previous;
        synchronized (MANAGERS) {
            previous = MANAGERS.put(server, new RadarNetworkManager(server));
        }
        if (previous != null && !previous.closed) {
            previous.close();
        }
        RadarDiagnostics.log("network_manager_started", null, null, null, 0L, "");
    }

    /**
     * Продвигает серверное время из ServerTickEvent.Pre и публикует окна с наступившей границей.
     * Блоковые тикеры этого серверного тика после вызова уже видят следующий epoch, поэтому
     * опоздавший отчёт предыдущего окна не может попасть в новую опубликованную версию.
     */
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        RadarNetworkManager manager;
        synchronized (MANAGERS) {
            manager = MANAGERS.get(server);
        }
        if (manager != null) {
            manager.beginServerTick();
        }
    }

    /** Удаляет менеджер и всё его состояние после остановки принадлежащего ему сервера. */
    public static void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        RadarNetworkManager manager;
        synchronized (MANAGERS) {
            manager = MANAGERS.remove(server);
        }
        if (manager != null && !manager.closed) {
            RadarDiagnostics.log("network_manager_stopping", null, null, null,
                    manager.serverTick, "networks=" + manager.networks.size());
            manager.close();
        }
    }

    /**
     * Возвращает монотонный номер тика текущего запуска.
     * Значение увеличивается в ServerTickEvent.Pre до обработки наступивших границ; блоковый
     * тикер должен сравнивать его с {@link ScanWindow#boundaryTick()}, а не вычислять период сам.
     * @return номер тика, используемый при сдаче скана и получении живых данных
     */
    public long currentServerTick() {
        checkActive();
        return serverTick;
    }

    /**
     * Регистрирует радар с общей политикой окон сети либо заменяет его прежнюю регистрацию.
     * Первый радар задаёт период и предел пропусков; несовместимая политика отклоняется до
     * изменения регистрации. Если сеть уже находится внутри окна, новый endpoint начинает
     * участие со следующего полного epoch, чтобы от него не ожидался отчёт за неполный проход.
     * @param endpoint идентичность загруженного радара
     * @param networkId сохранённый радаром идентификатор сети
     * @param policy период сети и число пропусков до удаления связи
     * @return новый токен этой регистрации
     * @throws IllegalArgumentException если политика конфликтует с работающими радарами сети
     */
    public RegistrationToken registerRadar(EndpointKey endpoint, NetworkId networkId, NetworkPolicy policy) {
        checkActive();
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(policy, "policy");
        try {
            ensureCompatiblePolicy(endpoint, networkId, policy);
        } catch (IllegalArgumentException exception) {
            RadarDiagnostics.log("radar_registration_rejected", networkId, endpoint, null, serverTick,
                    "reason=" + describe(exception) + " policy=" + policy);
            throw exception;
        }

        Set<NetworkId> changedNetworks = new HashSet<>();
        removePreviousRegistration(endpoint, changedNetworks);

        NetworkRuntime network = networks.get(networkId);
        if (network == null) {
            network = new NetworkRuntime(networkId);
            networks.put(networkId, network);
            RadarDiagnostics.log("network_runtime_created", networkId, null, null, serverTick,
                    "reason=first_radar_registration");
        }
        if (network.policy == null) {
            startPolicy(networkId, network, policy);
        }
        RegistrationToken token = RegistrationToken.create();
        long participatesFromEpoch = network.windowStartTick == serverTick
                ? network.currentEpoch
                : Math.incrementExact(network.currentEpoch);
        endpointIndex.put(endpoint, new Registration(networkId, EndpointRole.RADAR, token));
        network.radars.put(endpoint, new RadarState(token, participatesFromEpoch));
        changedNetworks.add(networkId);
        markChanged(changedNetworks);
        RadarDiagnostics.log("radar_registered", networkId, endpoint, participatesFromEpoch, serverTick,
                "token=" + token.value() + " policy=" + policy
                        + " activeRadars=" + network.radars.size()
                        + " participatesImmediately=" + (participatesFromEpoch == network.currentEpoch));
        return token;
    }

    /**
     * Регистрирует потребителя или монитор; эта регистрация не создаёт пустую сеть.
     * @param endpoint идентичность загруженного потребителя
     * @param networkId сохранённый потребителем идентификатор сети
     * @param role роль CONSUMER или MONITOR
     * @return новый токен регистрации
     */
    public RegistrationToken register(EndpointKey endpoint, NetworkId networkId, EndpointRole role) {
        checkActive();
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(role, "role");
        if (role == EndpointRole.RADAR) {
            throw new IllegalArgumentException("Use registerRadar to register a radar with a network policy");
        }

        Set<NetworkId> changedNetworks = new HashSet<>();
        removePreviousRegistration(endpoint, changedNetworks);
        RegistrationToken token = RegistrationToken.create();
        endpointIndex.put(endpoint, new Registration(networkId, role, token));
        markChanged(changedNetworks);
        RadarDiagnostics.log("consumer_registered", networkId, null, null, serverTick,
                "endpoint=" + endpoint + " role=" + role + " token=" + token.value());
        return token;
    }

    /**
     * Возвращает расписание ближайшего допустимого окна зарегистрированного радара.
     * Для радара, зарегистрированного посреди окна, результат указывает следующий полный epoch
     * и его границу, а {@code eligibleThisWindow} остаётся false до начала этого epoch.
     * @param radar endpoint зарегистрированного радара
     * @return расписание для актуальной регистрации либо пусто для неизвестного endpoint
     */
    public Optional<ScanWindow> scanWindowForRadar(EndpointKey radar) {
        checkActive();
        Objects.requireNonNull(radar, "radar");
        Registration registration = endpointIndex.get(radar);
        if (registration == null || registration.role != EndpointRole.RADAR) {
            return Optional.empty();
        }
        NetworkRuntime network = networks.get(registration.networkId);
        RadarState state = network == null ? null : network.radars.get(radar);
        if (network == null || network.policy == null || state == null || !state.token.equals(registration.token)) {
            return Optional.empty();
        }

        boolean eligibleThisWindow = state.participatesFromEpoch <= network.currentEpoch;
        long epoch = eligibleThisWindow ? network.currentEpoch : state.participatesFromEpoch;
        long boundaryTick = eligibleThisWindow
                ? network.nextBoundaryTick
                : Math.addExact(network.nextBoundaryTick, network.policy.periodTicks());
        return Optional.of(new ScanWindow(epoch, boundaryTick, network.policy.periodTicks(), eligibleThisWindow));
    }

    /**
     * Сдаёт полный результат прохода в закрытый буфер текущего окна.
     * Приём не меняет опубликованные обнаружения, возраст целей, live-данные или ревизию сети;
     * публикация атомарно выполняется на общей границе. Пустая коллекция является полноценным
     * отчётом, а повтор, неправильный epoch, чужое измерение, дубликат или опоздание отклоняются.
     * @param radar ключ зарегистрированного радара
     * @param networkId сеть этого радара
     * @param token токен его текущей регистрации
     * @param epoch номер окна, указанного расписанием радара
     * @param foundTargets все уникальные цели, найденные полным проходом
     * @return true, если отчёт принят в закрытый буфер; false для повтора, старого окна или неактивной регистрации
     */
    public boolean submitCompletedScan(
            EndpointKey radar,
            NetworkId networkId,
            RegistrationToken token,
            long epoch,
            Collection<RadarDetection> foundTargets
    ) {
        checkActive();
        Objects.requireNonNull(foundTargets, "foundTargets");
        Registration registration = matchingRegistration(radar, networkId, token, EndpointRole.RADAR);
        if (registration == null) {
            RadarDiagnostics.log("report_rejected", networkId, radar, epoch, serverTick,
                    "reason=" + registrationMismatch(radar, networkId, token, EndpointRole.RADAR));
            return false;
        }
        NetworkRuntime network = networks.get(networkId);
        RadarState state = network == null ? null : network.radars.get(radar);
        if (network == null) {
            return rejectReport(networkId, radar, epoch, "network_runtime_missing");
        }
        if (network.policy == null) {
            return rejectReport(networkId, radar, epoch, "network_policy_missing");
        }
        if (state == null || !state.token.equals(token)) {
            return rejectReport(networkId, radar, epoch, "radar_state_or_token_mismatch");
        }
        if (epoch != network.currentEpoch) {
            return rejectReport(networkId, radar, epoch,
                    "wrong_epoch currentEpoch=" + network.currentEpoch);
        }
        if (epoch < state.participatesFromEpoch) {
            return rejectReport(networkId, radar, epoch,
                    "radar_not_participating participatesFromEpoch=" + state.participatesFromEpoch);
        }
        if (serverTick >= network.nextBoundaryTick) {
            return rejectReport(networkId, radar, epoch,
                    "window_boundary_passed boundaryTick=" + network.nextBoundaryTick);
        }
        if (network.pending.containsKey(radar)) {
            return rejectReport(networkId, radar, epoch, "duplicate_report_for_window");
        }

        Map<TargetRef, RadarDetection> detections = new HashMap<>();
        for (RadarDetection detection : foundTargets) {
            if (detection == null) {
                return rejectReport(networkId, radar, epoch, "null_detection");
            }
            if (!detection.target().dimension().equals(radar.dimension())) {
                return rejectReport(networkId, radar, epoch,
                        "target_dimension_mismatch target=" + detection.target());
            }
            if (detections.putIfAbsent(detection.target(), detection) != null) {
                return rejectReport(networkId, radar, epoch,
                        "duplicate_target target=" + detection.target());
            }
        }
        network.pending.put(radar, new CompletedScan(epoch, token, serverTick, Map.copyOf(detections)));
        int expectedReports = participatingRadarCount(network, epoch);
        RadarDiagnostics.log("report_accepted", networkId, radar, epoch, serverTick,
                "found=" + detections.size() + " pendingReports=" + network.pending.size()
                        + " expectedReports=" + expectedReports + " boundaryTick=" + network.nextBoundaryTick);
        return true;
    }

    /**
     * Снимает регистрацию только при совпадении endpoint, сети и токена.
     * Для радара очистка немедленная: ожидающий отчёт и его связи удаляются до возврата,
     * при этом связи других радаров с теми же целями остаются. Старый token не может снять
     * регистрацию новой загрузки на том же endpoint.
     * @param endpoint ключ регистрации
     * @param networkId сеть, в которой endpoint был зарегистрирован
     * @param token токен именно этой загрузки прибора
     * @return true, если текущая регистрация совпала и была снята
     */
    public boolean unregister(EndpointKey endpoint, NetworkId networkId, RegistrationToken token) {
        checkActive();
        Registration registration = matchingRegistration(endpoint, networkId, token, null);
        if (registration == null) {
            RadarDiagnostics.log("registration_removal_rejected", networkId, endpoint, null, serverTick,
                    "reason=" + registrationMismatch(endpoint, networkId, token, null));
            return false;
        }
        endpointIndex.remove(endpoint);
        Set<NetworkId> changedNetworks = new HashSet<>();
        removeRegistrationData(endpoint, registration, changedNetworks);
        markChanged(changedNetworks);
        RadarDiagnostics.log(registration.role == EndpointRole.RADAR
                        ? "radar_unregistered" : "consumer_unregistered",
                networkId, registration.role == EndpointRole.RADAR ? endpoint : null,
                null, serverTick,
                "endpoint=" + endpoint + " role=" + registration.role
                        + " token=" + token.value() + " activeRadars=" + activeRadarCount(networkId));
        return true;
    }

    /**
     * Выполняет общий запрос всех целей, доступных текущей регистрации, в выбранном потребителем
     * формате. Перед делегированием менеджер обязательно сверяет endpoint, сохранённый NetworkId,
     * токен этой загрузки и query-роль. Старый экземпляр прибора поэтому не может прочитать
     * данные регистрации, заменившей его на том же endpoint.
     *
     * @param consumer endpoint загруженного потребителя
     * @param networkId сеть именно этой регистрации
     * @param token токен именно этой загрузки потребителя
     * @param projection типизированная проекция записи для конкретного прибора
     * @param <T> тип записи ответа
     * @return неизменяемый снимок полного набора доступных целей с epoch и revision
     */
    public <T> TargetSnapshot<T> queryTargets(
            EndpointKey consumer,
            NetworkId networkId,
            RegistrationToken token,
            TargetProjection<T> projection
    ) {
        checkActive();
        Objects.requireNonNull(projection, "projection");
        Registration registration = queryRegistration(consumer, networkId, token, "query_targets");
        if (registration == null) {
            return TargetSnapshot.empty(0, revisionSequence);
        }
        return RadarNetworkQueries.queryTargets(
                consumer, registration, projection, serverTick, revisionSequence, endpointIndex, networks);
    }

    /**
     * Возвращает подробные источники одной известной цели после проверки текущей тройки регистрации.
     * Полный список целей для этой точечной операции не строится.
     *
     * @param consumer endpoint загруженного потребителя
     * @param networkId сеть именно этой регистрации
     * @param token токен именно этой загрузки потребителя
     * @param target полная идентичность проверяемой цели
     * @return неизменяемые доступные источники и живые данные опубликованной версии
     */
    public TargetSources sourcesForTarget(
            EndpointKey consumer,
            NetworkId networkId,
            RegistrationToken token,
            TargetRef target
    ) {
        checkActive();
        Objects.requireNonNull(target, "target");
        Registration registration = queryRegistration(consumer, networkId, token, "sources_for_target");
        if (registration == null) {
            return new TargetSources(target, 0, revisionSequence, List.of(), Optional.empty());
        }
        return RadarNetworkQueries.sourcesForTarget(
                consumer, registration, target, serverTick, revisionSequence, endpointIndex, networks);
    }

    /** Возвращает ручной выбор только актуальному CONSUMER или MONITOR с верным токеном. */
    public Optional<TargetRef> selectedTarget(
            EndpointKey consumer,
            NetworkId networkId,
            RegistrationToken token
    ) {
        checkActive();
        Registration registration = queryRegistration(consumer, networkId, token, "selected_target");
        if (registration == null) {
            return Optional.empty();
        }
        return RadarNetworkQueries.selectedTarget(consumer, registration, serverTick, networks);
    }

    /**
     * Меняет ручной выбор только для актуальной регистрации MONITOR. Цель должна иметь хотя бы
     * один источник, обнаруживший её в последнем опубликованном окне среди доступных монитору
     * радаров. Потеря подтверждения позднее сама по себе не очищает сохранённый выбор.
     *
     * @param monitor endpoint загруженного монитора
     * @param networkId сеть именно этой регистрации
     * @param token токен именно этой загрузки монитора
     * @param target цель для выбора или {@code null} для очистки
     * @return true, если операция разрешена и применена
     */
    public boolean selectTarget(
            EndpointKey monitor,
            NetworkId networkId,
            RegistrationToken token,
            TargetRef target
    ) {
        checkActive();
        Registration registration = matchingRegistration(monitor, networkId, token, EndpointRole.MONITOR);
        if (registration == null) {
            logConsumerAccessRejected("select_target", monitor, networkId, token, EndpointRole.MONITOR);
            return false;
        }

        NetworkRuntime network = networks.get(networkId);
        if (target == null) {
            boolean changed = network != null && network.selectedTarget != null;
            if (changed) {
                network.selectedTarget = null;
                markChanged(Set.of(networkId));
                removeNetworkIfUnused(networkId, network);
            }
            RadarDiagnostics.log("target_selection_cleared", networkId, null,
                    network == null ? null : network.publishedEpoch, serverTick,
                    "monitor=" + monitor + " changed=" + changed);
            return true;
        }
        if (network == null) {
            RadarDiagnostics.log("target_selection_rejected", networkId, null, null, serverTick,
                    "monitor=" + monitor + " target=" + target + " reason=network_runtime_missing");
            return false;
        }
        TargetEntry entry = network.targets.get(target);
        if (entry == null) {
            RadarDiagnostics.log("target_selection_rejected", networkId, null,
                    network.publishedEpoch, serverTick,
                    "monitor=" + monitor + " target=" + target + " reason=target_not_published");
            return false;
        }
        var accessibleRadars = RadarNetworkQueries.accessibleRadars(
                monitor, registration, network, endpointIndex);
        if (!RadarNetworkQueries.hasFreshAccessibleSource(
                accessibleRadars, target, network.publishedEpoch)) {
            RadarDiagnostics.log("target_selection_rejected", networkId, null,
                    network.publishedEpoch, serverTick,
                    "monitor=" + monitor + " target=" + target + " reason=no_fresh_accessible_source");
            return false;
        }
        if (!target.equals(network.selectedTarget)) {
            network.selectedTarget = target;
            markChanged(Set.of(networkId));
        }
        RadarDiagnostics.log("target_selection_accepted", networkId, null,
                network.publishedEpoch, serverTick,
                "monitor=" + monitor + " target=" + target);
        return true;
    }

    /** Увеличивает общий тик и закрывает все окна, чья граница наступила до обслуживания этого тика. */
    private void beginServerTick() {
        checkActive();
        serverTick = Math.incrementExact(serverTick);
        processDueBoundaries();
    }

    /**
     * Обрабатывает только сети с наступившей границей, используя очередь по времени
     * вместо обхода всех сетей каждый тик.
     * Для каждого окна порядок фиксирован: применить полные отчёты или отметить пропуски,
     * получить live-значения, продвинуть epoch/revision и лишь затем вывести опубликованный снимок.
     */
    private void processDueBoundaries() {
        while (!scheduledBoundaries.isEmpty() && scheduledBoundaries.firstKey() <= serverTick) {
            Map.Entry<Long, Set<NetworkId>> boundary = scheduledBoundaries.pollFirstEntry();
            long boundaryTick = boundary.getKey();
            for (NetworkId networkId : boundary.getValue()) {
                NetworkRuntime network = networks.get(networkId);
                if (network == null || network.policy == null || network.nextBoundaryTick != boundaryTick) {
                    continue;
                }
                long closingEpoch = network.currentEpoch;
                int expectedReports = participatingRadarCount(network, closingEpoch);
                RadarDiagnostics.log("window_close_started", networkId, null, closingEpoch, serverTick,
                        "boundaryTick=" + boundaryTick + " activeRadars=" + network.radars.size()
                                + " pendingReports=" + network.pending.size()
                                + " expectedReports=" + expectedReports);
                // Публикация синхронно завершает обе стороны индексов и LiveResult; пока вызов
                // не вернулся, серверный поток не обслуживает ни новый скан, ни чтение потребителя.
                publisher.publishWindow(network, serverTick);
                network.currentEpoch = Math.incrementExact(network.publishedEpoch);
                network.windowStartTick = boundaryTick;
                network.nextBoundaryTick = Math.addExact(boundaryTick, network.policy.periodTicks());
                network.revision = nextRevision();
                scheduleBoundary(networkId, network.nextBoundaryTick);
                RadarDiagnostics.log("window_closed", networkId, null, closingEpoch, serverTick,
                        "revision=" + network.revision + " targets=" + network.targets.size()
                                + " nextEpoch=" + network.currentEpoch
                                + " nextBoundaryTick=" + network.nextBoundaryTick);
                RadarDiagnostics.log("window_opened", networkId, null, network.currentEpoch, serverTick,
                        "boundaryTick=" + network.nextBoundaryTick
                                + " activeRadars=" + network.radars.size());
                RadarNetworkDiagnostics.logPublishedSnapshot(network, serverTick);
            }
        }
    }

    /** Проверяет политику сети до снятия прежней регистрации, чтобы конфликт не менял текущее состояние. */
    private void ensureCompatiblePolicy(EndpointKey endpoint, NetworkId networkId, NetworkPolicy policy) {
        NetworkRuntime network = networks.get(networkId);
        if (network == null || network.policy == null) {
            return;
        }
        for (EndpointKey existingRadar : network.radars.keySet()) {
            if (!existingRadar.equals(endpoint) && !network.policy.equals(policy)) {
                throw new IllegalArgumentException("Radar network already uses a different scan policy");
            }
        }
    }

    /** Снимает прежнюю регистрацию endpoint и собирает сети, чья видимая версия должна получить ревизию. */
    private void removePreviousRegistration(EndpointKey endpoint, Set<NetworkId> changedNetworks) {
        Registration previous = endpointIndex.remove(endpoint);
        if (previous != null) {
            RadarDiagnostics.log("registration_replaced", previous.networkId,
                    previous.role == EndpointRole.RADAR ? endpoint : null,
                    null, serverTick,
                    "endpoint=" + endpoint + " role=" + previous.role
                            + " previousToken=" + previous.token.value());
            removeRegistrationData(endpoint, previous, changedNetworks);
        }
    }

    /**
     * Удаляет runtime-состояние снятого радара и отменяет окно после ухода последнего радара.
     * Эта очистка синхронна с unload/removal: сеть не ждёт следующей границы, чтобы отозвать источник.
     */
    private void removeRegistrationData(
            EndpointKey endpoint,
            Registration registration,
            Set<NetworkId> changedNetworks
    ) {
        if (registration.role != EndpointRole.RADAR) {
            return;
        }
        NetworkRuntime network = networks.get(registration.networkId);
        if (network == null) {
            return;
        }
        boolean pendingRemoved = network.pending.remove(endpoint) != null;
        RadarState state = network.radars.remove(endpoint);
        if (state != null) {
            publisher.removeRadarLinks(network, endpoint, state, "radar_unregistered", serverTick);
            changedNetworks.add(registration.networkId);
        }
        RadarDiagnostics.log("radar_state_removed", registration.networkId, endpoint,
                network.policy == null ? null : network.currentEpoch, serverTick,
                "pendingReportRemoved=" + pendingRemoved + " removedRadarState=" + (state != null)
                        + " activeRadars=" + network.radars.size());
        if (network.radars.isEmpty()) {
            stopPolicy(registration.networkId, network);
        }
        removeNetworkIfUnused(registration.networkId, network);
    }

    /**
     * Создаёт epoch 1 от текущего serverTick и ставит его первую границу через periodTicks.
     * Все радары этой сети используют одну политику, поэтому границы не зависят от времени
     * установки каждого последующего endpoint.
     */
    private void startPolicy(NetworkId networkId, NetworkRuntime network, NetworkPolicy policy) {
        network.policy = policy;
        network.currentEpoch = 1;
        network.publishedEpoch = 0;
        network.windowStartTick = serverTick;
        network.nextBoundaryTick = Math.addExact(serverTick, policy.periodTicks());
        scheduleBoundary(networkId, network.nextBoundaryTick);
        RadarDiagnostics.log("window_opened", networkId, null, network.currentEpoch, serverTick,
                "boundaryTick=" + network.nextBoundaryTick + " policy=" + policy);
    }

    /**
     * Снимает границу и сбрасывает runtime-данные, когда в сети не осталось радаров.
     * NetworkRuntime может остаться только ради ранее сохранённого ручного выбора; новые
     * обнаружения начнут новую последовательность epoch при следующей регистрации радара.
     */
    private void stopPolicy(NetworkId networkId, NetworkRuntime network) {
        RadarDiagnostics.log("network_policy_stopped", networkId, null,
                network.policy == null ? null : network.currentEpoch, serverTick,
                "activeRadars=" + network.radars.size() + " pendingReports=" + network.pending.size()
                        + " targets=" + network.targets.size());
        if (network.policy != null) {
            unscheduleBoundary(networkId, network.nextBoundaryTick);
        }
        network.policy = null;
        network.currentEpoch = 0;
        network.publishedEpoch = 0;
        network.windowStartTick = -1;
        network.nextBoundaryTick = -1;
        network.pending.clear();
        network.targets.clear();
    }

    /** Добавляет сеть в группу, обрабатываемую на заданном серверном тике. */
    private void scheduleBoundary(NetworkId networkId, long boundaryTick) {
        scheduledBoundaries.computeIfAbsent(boundaryTick, ignored -> new HashSet<>()).add(networkId);
    }

    /** Удаляет сеть из расписания и освобождает пустую группу границы. */
    private void unscheduleBoundary(NetworkId networkId, long boundaryTick) {
        Set<NetworkId> networksAtBoundary = scheduledBoundaries.get(boundaryTick);
        if (networksAtBoundary != null) {
            networksAtBoundary.remove(networkId);
            if (networksAtBoundary.isEmpty()) {
                scheduledBoundaries.remove(boundaryTick);
            }
        }
    }

    /** Удаляет runtime-сеть, в которой не осталось радаров, публикаций или ручного выбора. */
    private void removeNetworkIfUnused(NetworkId id, NetworkRuntime network) {
        if (network.radars.isEmpty() && network.targets.isEmpty() && network.selectedTarget == null) {
            if (networks.remove(id, network)) {
                RadarDiagnostics.log("network_runtime_deleted", id, null, null, serverTick,
                        "reason=unused");
            }
        }
    }

    /**
     * Назначает новую ревизию сетям, чьё видимое состояние изменилось вне границы окна.
     * Если runtime уже удалён, глобальная последовательность всё равно отражает изменение
     * в последующих пустых ответах без отдельной tombstone-записи.
     */
    private void markChanged(Set<NetworkId> changedNetworks) {
        for (NetworkId networkId : changedNetworks) {
            long revision = nextRevision();
            NetworkRuntime network = networks.get(networkId);
            if (network != null) {
                network.revision = revision;
            }
            RadarDiagnostics.log("network_revision_changed", networkId, null,
                    network == null ? null : network.publishedEpoch, serverTick,
                    "revision=" + revision + " runtimePresent=" + (network != null));
        }
    }

    /** Выдаёт следующую уникальную в пределах запуска сервера ревизию видимого состояния. */
    private long nextRevision() {
        revisionSequence = Math.incrementExact(revisionSequence);
        return revisionSequence;
    }

    /** Проверяет NetworkId, токен и требуемую роль перед мутацией регистрации. */
    private Registration matchingRegistration(
            EndpointKey endpoint,
            NetworkId networkId,
            RegistrationToken token,
            EndpointRole requiredRole
    ) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(token, "token");
        Registration registration = endpointIndex.get(endpoint);
        if (registration == null
                || !registration.networkId.equals(networkId)
                || !registration.token.equals(token)
                || (requiredRole != null && registration.role != requiredRole)) {
            return null;
        }
        return registration;
    }

    /** Проверяет тройку регистрации и query-роль до любого чтения опубликованных данных. */
    private Registration queryRegistration(
            EndpointKey endpoint,
            NetworkId networkId,
            RegistrationToken token,
            String operation
    ) {
        Registration registration = matchingRegistration(endpoint, networkId, token, null);
        if (registration == null || !registration.role.canQueryNetwork()) {
            logConsumerAccessRejected(operation, endpoint, networkId, token, null);
            return null;
        }
        return registration;
    }

    /** Подробные отказы чтения включаются отдельно, чтобы частые запросы не засоряли INFO. */
    private void logConsumerAccessRejected(
            String operation,
            EndpointKey endpoint,
            NetworkId networkId,
            RegistrationToken token,
            EndpointRole requiredRole
    ) {
        if (!RadarDiagnostics.queryDiagnosticsEnabled()) {
            return;
        }
        Registration current = endpoint == null ? null : endpointIndex.get(endpoint);
        String reason;
        if (current == null) {
            reason = "endpoint_not_registered";
        } else if (networkId == null || !current.networkId.equals(networkId)) {
            reason = "network_id_mismatch";
        } else if (token == null || !current.token.equals(token)) {
            reason = "registration_token_mismatch";
        } else if (requiredRole != null && current.role != requiredRole) {
            reason = "role_mismatch registeredRole=" + current.role + " requiredRole=" + requiredRole;
        } else if (!current.role.canQueryNetwork()) {
            reason = "role_not_queryable registeredRole=" + current.role;
        } else {
            reason = "registration_mismatch";
        }
        RadarDiagnostics.log("consumer_access_rejected", networkId, endpoint, null, serverTick,
                "operation=" + operation + " reason=" + reason);
    }

    /** Runtime-проверка для ConsumerConnection, чтобы close после ServerStopped был безопасен. */
    boolean isClosed() {
        return closed;
    }

    /**
     * Read-only проверка срока жизни consumer-регистрации для ConsumerConnection.isOpen().
     * Endpoint-индекс содержит только последнюю загрузку; совпадение всех трёх значений
     * отличает её от старой связи, оставшейся у выгруженного экземпляра на том же месте.
     * Метод не создаёт NetworkRuntime, не меняет ревизию и не пишет журнал на каждый вызов.
     */
    boolean isCurrentConsumerRegistration(
            EndpointKey endpoint,
            NetworkId networkId,
            RegistrationToken token
    ) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Radar network access must run on the server thread");
        }
        if (closed || endpoint == null || networkId == null || token == null) {
            return false;
        }
        Registration current = endpointIndex.get(endpoint);
        return current != null
                && current.networkId.equals(networkId)
                && current.token.equals(token)
                && current.role.canQueryNetwork();
    }

    /** Отказывает отчёту без изменения сети и записывает причину вместе с заполненностью буфера. */
    private boolean rejectReport(NetworkId networkId, EndpointKey radar, long epoch, String reason) {
        NetworkRuntime network = networks.get(networkId);
        RadarDiagnostics.log("report_rejected", networkId, radar, epoch, serverTick,
                "reason=" + reason
                        + " pendingReports=" + (network == null ? 0 : network.pending.size())
                        + " expectedReports=" + (network == null ? 0 : participatingRadarCount(network, epoch)));
        return false;
    }

    /** Возвращает конкретное отличие активной регистрации от переданных network, token или role. */
    private String registrationMismatch(
            EndpointKey endpoint,
            NetworkId networkId,
            RegistrationToken token,
            EndpointRole requiredRole
    ) {
        Registration registration = endpointIndex.get(endpoint);
        if (registration == null) {
            return "endpoint_not_registered";
        }
        if (!registration.networkId.equals(networkId)) {
            return "network_id_mismatch registeredNetwork=" + registration.networkId.value();
        }
        if (!registration.token.equals(token)) {
            return "registration_token_mismatch";
        }
        if (requiredRole != null && registration.role != requiredRole) {
            return "role_mismatch registeredRole=" + registration.role + " requiredRole=" + requiredRole;
        }
        return "registration_mismatch";
    }

    /** Считает только радары, от которых сеть вправе ожидать полный отчёт для этого epoch. */
    private int participatingRadarCount(NetworkRuntime network, long epoch) {
        int count = 0;
        for (RadarState radar : network.radars.values()) {
            if (radar.participatesFromEpoch <= epoch) {
                count++;
            }
        }
        return count;
    }

    private int activeRadarCount(NetworkId networkId) {
        NetworkRuntime network = networks.get(networkId);
        return network == null ? 0 : network.radars.size();
    }

    /** Проверяет принадлежность потоку сервера перед доступом к любому изменяемому состоянию менеджера. */
    private void checkActive() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Radar network access must run on the server thread");
        }
        if (closed) {
            throw new IllegalStateException("Radar network manager is closed");
        }
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ":" + message.replace(' ', '_'));
    }

    /** Очищает все карты и расписания при остановке сервера, после чего запрещает повторное использование. */
    private void close() {
        checkActive();
        for (Map.Entry<NetworkId, NetworkRuntime> entry : networks.entrySet()) {
            NetworkRuntime network = entry.getValue();
            RadarDiagnostics.log("network_runtime_deleted", entry.getKey(), null,
                    network.policy == null ? null : network.currentEpoch, serverTick,
                    "reason=server_stopped activeRadars=" + network.radars.size()
                            + " pendingReports=" + network.pending.size()
                            + " targets=" + network.targets.size());
        }
        endpointIndex.clear();
        networks.clear();
        scheduledBoundaries.clear();
        closed = true;
    }

}
