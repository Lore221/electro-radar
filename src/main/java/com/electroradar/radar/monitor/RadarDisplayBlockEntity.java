package com.electroradar.radar.monitor;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.monitor.network.MonitorPayloads;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.EndpointRole;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSnapshot;
import com.electroradar.radar.network.TargetView;
import com.electroradar.radar.network.ScanWindow;
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.visual.RadarVisualProfileSource;

/**
 * Сохраняет идентичность одной панели, а у панели-корня дополнительно владеет единственной
 * runtime-связью с радарной сетью и последним готовым экранным снимком.
 *
 * <p>Все обращения к RadarNetworkManager происходят в серверном ticker или обработчике payload,
 * то есть на основном потоке мира. DisplayId, корень и NetworkId копируются на панели только
 * для восстановления физической сборки и связи после удаления корня; токен регистрации и цели
 * никогда не записываются в NBT. Связь закрывается до удаления, выгрузки и повышения нового корня.</p>
 */
public final class RadarDisplayBlockEntity extends BlockEntity {
    private static final String DISPLAY_ID_TAG = "electroradar_display_id";
    private static final String ROOT_POS_TAG = "electroradar_root_pos";
    private static final String NETWORK_ID_TAG = "electroradar_network_id";
    private static final String RECT_ORIGIN_TAG = "electroradar_rect_origin";
    private static final String RECT_WIDTH_TAG = "electroradar_rect_width";
    private static final String RECT_HEIGHT_TAG = "electroradar_rect_height";
    private static final String STRUCTURE_VERSION_TAG = "electroradar_structure_version";
    private static final String LOAD_ID_TAG = "electroradar_load_id";
    private static final long POLL_PERIOD_TICKS = 5L;
    /** Серверное игровое время следующей границы, на которой нужно перечитать опубликованный снимок. */
    private long nextSnapshotRefreshGameTime = -1L;

    private UUID displayId;
    private BlockPos rootPosition;
    private NetworkId networkId;
    private BlockPos rectangleOrigin;
    private int rectangleWidth;
    private int rectangleHeight;
    private long structureVersion;

    /** Новый UUID на каждом onLoad; он никогда не сохраняется в сохранение мира. */
    private UUID loadId = UUID.randomUUID();
    private ConsumerConnection connection;
    private boolean assemblyDirty = true;
    /** Не повторяет один и тот же ERROR на каждом серверном тике при недоступной регистрации. */
    private boolean registrationFailureLogged;
    /** Не повторяет stack trace проверки одного runtime-handle до следующей успешной проверки. */
    private boolean connectionCheckFailureLogged;
    /** Удерживает по одной записи ошибки чтения профиля на endpoint до его восстановления. */
    private final Set<EndpointKey> visualProfileFailuresLogged = new HashSet<>();
    private boolean visualProfileCollectionFailureLogged;
    private MonitorSnapshotData latestSnapshot;
    /**
     * Номер последнего собранного снимка этой загрузки. Он только растёт, и по нему клиент
     * упорядочивает снимки. Сбрасывать его безопасно лишь вместе со сменой loadId: снимки
     * новой загрузки клиент хранит под другим ключом.
     */
    private long snapshotSequence;
    private long lastSentSequence = -1L;

    public RadarDisplayBlockEntity(BlockPos position, BlockState state) {
        super(RadarDisplayContent.RADAR_DISPLAY_ENTITY.get(), position, state);
    }

    /** При загрузке новая runtime-связь создаётся позднее на первом серверном тике. */
    @Override
    public void onLoad() {
        super.onLoad();
        connection = null;
        clearSnapshotRefreshSchedule();
        registrationFailureLogged = false;
        connectionCheckFailureLogged = false;
        visualProfileFailuresLogged.clear();
        visualProfileCollectionFailureLogged = false;
        latestSnapshot = null;
        lastSentSequence = -1L;
        assemblyDirty = true;
        if (level instanceof ServerLevel) {
            loadId = UUID.randomUUID();
            markRootDirtyIfPresent();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writePersistentData(tag);
        // Runtime UUID попадает только в сетевой update tag, но не в постоянный NBT мира.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readPersistentData(tag);
        if (tag.hasUUID(LOAD_ID_TAG)) {
            loadId = tag.getUUID(LOAD_ID_TAG);
        }
        connection = null;
        clearSnapshotRefreshSchedule();
        latestSnapshot = null;
        assemblyDirty = true;
    }

    /** Передаёт на клиент только идентичность панели, геометрию и форму блока. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        super.saveAdditional(tag, registries);
        writeClientData(tag);
        tag.putUUID(LOAD_ID_TAG, loadId);
        return tag;
    }

    /** Создаёт пакет для sendBlockUpdated; базовый BlockEntity в 1.21.1 возвращает null. */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readPersistentData(tag);
        if (tag.hasUUID(LOAD_ID_TAG)) {
            loadId = tag.getUUID(LOAD_ID_TAG);
        }
    }

    /** Закрывает связь идемпотентно; на выгрузке никакие панели не объявляются разрушенными. */
    @Override
    public void setRemoved() {
        closeConnection("block_entity_removed_or_unloaded");
        super.setRemoved();
    }

    /** Серверный тикер вызывается у всех панелей, но тяжёлые действия выполняются только у корня. */
    public static void serverTick(Level level, BlockPos position, BlockState state,
                                  RadarDisplayBlockEntity display) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        display.ensureIdentity();
        if (!display.isRoot()) {
            RadarDisplayAssembly.recoverOrphanedRoot(serverLevel, display);
            if (!display.isRoot()) return;
        }
        if (display.assemblyDirty) {
            RadarDisplayAssembly.resolve(display);
        }
        display.ensureConnection(serverLevel);
        long gameTime = serverLevel.getGameTime();
        boolean ordinaryPoll = Math.floorMod(
                gameTime + Math.floorMod(position.asLong(), POLL_PERIOD_TICKS), POLL_PERIOD_TICKS) == 0;
        boolean scheduledBoundary = display.nextSnapshotRefreshGameTime >= 0L
                && gameTime >= display.nextSnapshotRefreshGameTime;
        if (ordinaryPoll || scheduledBoundary) {
            // Менеджер уже опубликовал границу в ServerTickEvent.Pre. Этот единственный
            // внеочередной вызов увидит новую эпоху; запрос целей не выполняется каждый тик.
            display.refreshAndBroadcast(false, null);
        }
    }

    /** Создаёт базовую идентичность самостоятельной панели и применяет привязку её предмета. */
    void initializeStandalone(NetworkId boundNetworkId) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean created = displayId == null;
        BlockPos oldRoot = rootPosition();
        if (displayId == null) {
            displayId = UUID.randomUUID();
        }
        rootPosition = worldPosition;
        networkId = boundNetworkId;
        loadId = UUID.randomUUID();
        assemblyDirty = true;
        if (created) {
            RadarMonitorDiagnostics.displayCreated(serverLevel, this, "item_placement");
        } else if (!oldRoot.equals(worldPosition)) {
            RadarMonitorDiagnostics.rootChanged(serverLevel, this, oldRoot, worldPosition,
                    "standalone_initialization");
        }
        setChanged();
        syncClient();
        RadarDisplayAssembly.markDirtyAround(level, worldPosition, displayId);
    }

    /** Возвращает true, если эта панель является единственным владельцем сетевой связи. */
    public boolean isRoot() {
        return rootPosition == null || rootPosition.equals(worldPosition);
    }

    public UUID displayId() {
        return displayId;
    }

    public UUID loadId() {
        return loadId;
    }

    public BlockPos rootPosition() {
        return rootPosition == null ? worldPosition : rootPosition;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public BlockPos rectangleOrigin() {
        return rectangleOrigin == null ? rootPosition() : rectangleOrigin;
    }

    public int rectangleWidth() {
        return rectangleWidth;
    }

    public int rectangleHeight() {
        return rectangleHeight;
    }

    public long structureVersion() {
        return structureVersion;
    }

    /** Позволяет Create placement helper проверить сеть до изменения уже стоящего дисплея. */
    boolean canJoinNetwork(NetworkId itemNetworkId) {
        NetworkId currentNetworkId = networkId;
        RadarDisplayBlockEntity root = currentNetworkId == null ? loadedRoot() : null;
        if (root != null) currentNetworkId = root.networkId;
        return itemNetworkId == null || currentNetworkId == null || itemNetworkId.equals(currentNetworkId);
    }

    /** Присоединяет только что размещённую панель к тому же физическому дисплею, что и корень. */
    void joinDisplay(RadarDisplayBlockEntity member, NetworkId itemNetworkId) {
        if (!(level instanceof ServerLevel) || member == null || member.displayId == null) {
            return;
        }
        if (!canJoinNetwork(itemNetworkId)) {
            throw new IllegalArgumentException("A panel bound to another network cannot join this display");
        }
        // Если панель до этого была самостоятельным корнем, сначала снять её регистрацию.
        closeConnection("panel_joined_display");
        displayId = member.displayId;
        rootPosition = member.rootPosition();
        RadarDisplayBlockEntity existingRoot = member.loadedRoot();
        networkId = member.networkId != null ? member.networkId
                : existingRoot == null ? null : existingRoot.networkId;
        if (networkId == null) {
            networkId = itemNetworkId;
            if (itemNetworkId != null && level.hasChunkAt(rootPosition)) {
                if (level.getBlockEntity(rootPosition) instanceof RadarDisplayBlockEntity root) {
                    root.networkId = itemNetworkId;
                    root.latestSnapshot = null;
                    root.setChanged();
                }
            }
        }
        rectangleOrigin = member.rectangleOrigin;
        rectangleWidth = member.rectangleWidth;
        rectangleHeight = member.rectangleHeight;
        structureVersion = Math.max(structureVersion, member.structureVersion);
        assemblyDirty = true;
        latestSnapshot = null;
        setChanged();
        syncClient();
        RadarDisplayAssembly.markDirtyAround(level, worldPosition, displayId);
    }

    /** Возвращает корневой BlockEntity только если его чанк уже загружен. */
    RadarDisplayBlockEntity loadedRoot() {
        if (level == null || !level.hasChunkAt(rootPosition())) {
            return null;
        }
        BlockEntity candidate = level.getBlockEntity(rootPosition());
        return candidate instanceof RadarDisplayBlockEntity root && root.isRoot()
                && displayId != null && displayId.equals(root.displayId) ? root : null;
    }

    /** Открывает GUI конкретному игроку с немедленным снимком; обновления не открывают его повторно. */
    void openScreen(ServerPlayer player) {
        if (!(level instanceof ServerLevel) || !isRoot()) {
            return;
        }
        ensureIdentity();
        if (assemblyDirty) RadarDisplayAssembly.resolve(this);
        refreshAndBroadcast(true, player);
    }

    /**
     * Проверяет команду выбора по текущей серверной сборке и правилам доступа.
     * Клиентский NetworkId не принимается: полномочие даёт только загруженный корень и его связь.
     */
    public boolean handleSelection(ServerPlayer player, UUID requestedDisplayId, TargetRef target) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return rejectSelection(null, player, requestedDisplayId, target, "not_server_level");
        }
        if (!isRoot()) return rejectSelection(serverLevel, player, requestedDisplayId, target, "not_root");
        if (!worldPosition.equals(rootPosition())) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target, "root_position_mismatch");
        }
        if (displayId == null) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target, "display_id_missing");
        }
        if (!displayId.equals(requestedDisplayId)) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target, "display_id_mismatch");
        }
        if (!player.serverLevel().dimension().equals(serverLevel.dimension())) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target, "dimension_mismatch");
        }
        if (!serverLevel.hasChunkAt(worldPosition)) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target, "root_chunk_not_loaded");
        }
        // Команда может прийти между изменением блока и обычным ticker, поэтому сначала
        // подтверждаем текущий прямоугольник, затем проверяем дистанцию до его панелей.
        if (assemblyDirty) RadarDisplayAssembly.resolve(this);
        if (!isWithinActivePanelReach(serverLevel, player)) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target,
                    "outside_active_panel_reach");
        }
        ensureConnection(serverLevel);
        if (connection == null) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target,
                    "consumer_connection_unavailable");
        }
        if (!isConnectionOpen()) {
            return rejectSelection(serverLevel, player, requestedDisplayId, target,
                    "consumer_connection_not_open");
        }
        try {
            if (!connection.selectTarget(target)) {
                return rejectSelection(serverLevel, player, requestedDisplayId, target,
                        "network_manager_rejected_target_or_access");
            }
        } catch (RuntimeException exception) {
            RadarMonitorDiagnostics.selectionFailed(serverLevel, this, player, target, exception);
            return rejectSelection(serverLevel, player, requestedDisplayId, target,
                    "network_manager_exception");
        }
        RadarMonitorDiagnostics.selectionAccepted(serverLevel, this, player, target);
        refreshAndBroadcast(false, null);
        return true;
    }

    /** Пишет отказ только при обработке явной команды выбора, а не в серверном ticker. */
    private boolean rejectSelection(ServerLevel serverLevel, ServerPlayer player,
                                    UUID requestedDisplayId, TargetRef target, String reason) {
        RadarMonitorDiagnostics.selectionRejected(serverLevel, this, worldPosition,
                requestedDisplayId, player, target, reason);
        return false;
    }

    /** Отправляет последний снимок новому наблюдателю после передачи его чанка. */
    public void sendTo(ServerPlayer player) {
        if (!isRoot() || !(level instanceof ServerLevel)) return;
        ensureIdentity();
        if (assemblyDirty) RadarDisplayAssembly.resolve(this);
        refreshAndBroadcast(false, player);
    }

    private void ensureIdentity() {
        boolean created = displayId == null;
        if (displayId == null) {
            displayId = UUID.randomUUID();
        }
        if (rootPosition == null) {
            rootPosition = worldPosition;
        }
        if (rectangleOrigin == null) {
            rectangleOrigin = worldPosition;
            rectangleWidth = 1;
            rectangleHeight = 1;
        }
        if (created && level instanceof ServerLevel serverLevel) {
            RadarMonitorDiagnostics.displayCreated(serverLevel, this, "missing_saved_identity");
        }
    }

    private void ensureConnection(ServerLevel serverLevel) {
        if (!isRoot() || networkId == null) {
            closeConnection("root_or_network_unavailable");
            return;
        }
        if (connection != null && isConnectionOpen()) {
            return;
        }
        if (connection != null) {
            closeConnection("connection_not_open");
        }
        try {
            connection = new ConsumerConnection(serverLevel.getServer(),
                    new EndpointKey.Static(serverLevel.dimension(), worldPosition),
                    networkId, EndpointRole.MONITOR);
            registrationFailureLogged = false;
            connectionCheckFailureLogged = false;
            RadarMonitorDiagnostics.consumerConnectionOpened(serverLevel, this);
        } catch (RuntimeException exception) {
            connection = null;
            clearSnapshotRefreshSchedule();
            // Следующее обращение повторит регистрацию; одинаковый отказ логируется один раз.
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarMonitorDiagnostics.registrationFailed(serverLevel, this,
                        "operation=ConsumerConnection", exception);
            }
        }
    }

    /**
     * Проверяет расстояние до любой активной панели текущего прямоугольника.
     * Неизвестные чанки не загружаются: достаточно находиться рядом с одной загруженной панелью,
     * если она всё ещё принадлежит этому корню и сервер пометил её как активную.
     */
    private boolean isWithinActivePanelReach(ServerLevel serverLevel, ServerPlayer player) {
        if (rectangleWidth < 1 || rectangleHeight < 1
                || rectangleWidth > RadarDisplayStructureResolver.MAX_SIZE
                || rectangleHeight > RadarDisplayStructureResolver.MAX_SIZE
                || displayId == null) {
            return false;
        }
        Direction facing = getBlockState().getValue(RadarDisplayBlock.FACING);
        BlockPos origin = rectangleOrigin();
        for (int u = 0; u < rectangleWidth; u++) {
            for (int v = 0; v < rectangleHeight; v++) {
                BlockPos panelPosition = RadarDisplayStructureResolver.at(origin, facing, u, v);
                if (!serverLevel.hasChunkAt(panelPosition)) continue;
                BlockState panelState = serverLevel.getBlockState(panelPosition);
                if (!panelState.is(RadarDisplayContent.RADAR_DISPLAY_BLOCK.get())
                        || !panelState.getValue(RadarDisplayBlock.ACTIVE)
                        || !(serverLevel.getBlockEntity(panelPosition) instanceof RadarDisplayBlockEntity panel)
                        || !displayId.equals(panel.displayId())
                        || !worldPosition.equals(panel.rootPosition())) {
                    continue;
                }
                if (player.distanceToSqr(Vec3.atCenterOf(panelPosition)) <= 64.0D) return true;
            }
        }
        return false;
    }

    /**
     * Отвязывает runtime-handle до смены корня или удаления BlockEntity.
     * Исключение close записывается один раз для этого handle, после чего объект больше не
     * используется и не сможет повторять одинаковую ошибку на каждом серверном тике.
     */
    private void closeConnection(String reason) {
        // После закрытия consumer больше нельзя полагаться на сохранённое расписание.
        // Обычный редкий опрос возобновит сбор, если связь позднее восстановится.
        clearSnapshotRefreshSchedule();
        ConsumerConnection previous = connection;
        connection = null;
        if (previous != null) {
            boolean closed = true;
            try {
                previous.close();
            } catch (RuntimeException exception) {
                closed = false;
                ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
                RadarMonitorDiagnostics.connectionCloseFailed(serverLevel, this, reason, exception);
            }
            ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
            RadarMonitorDiagnostics.consumerConnectionClosed(serverLevel, this, reason, closed);
            connectionCheckFailureLogged = false;
            latestSnapshot = null;
        }
    }

    private void refreshAndBroadcast(boolean openGui, ServerPlayer recipient) {
        if (!(level instanceof ServerLevel serverLevel) || !isRoot()) {
            return;
        }
        ensureConnection(serverLevel);
        TargetSnapshot<MonitorTargetData> targetSnapshot = connection == null || !isConnectionOpen()
                ? TargetSnapshot.empty(0, 0)
                : connection.queryTargets(RadarDisplayBlockEntity::projectTarget);
        VisualWindow visualWindow = collectVisualWindow(serverLevel);
        scheduleSnapshotRefresh(serverLevel, visualWindow);

        List<MonitorTargetData> targets = targetSnapshot.targets();
        TargetRef chosen = null;
        boolean unavailable = false;
        if (connection != null && isConnectionOpen()) {
            Optional<TargetRef> networkSelection = connection.selectedTarget();
            if (networkSelection.isPresent()) {
                TargetRef requested = networkSelection.get();
                if (targets.stream().anyMatch(target -> target.target().equals(requested))) {
                    chosen = requested;
                } else {
                    // Не передаём клиенту даже идентичность цели, недоступной этому монитору.
                    unavailable = true;
                }
            }
        }

        boolean linked = networkId != null;
        boolean sameVersion = latestSnapshot != null
                && latestSnapshot.structureVersion() == structureVersion
                && latestSnapshot.publishedEpoch() == targetSnapshot.publishedEpoch()
                && latestSnapshot.revision() == targetSnapshot.revision()
                && latestSnapshot.linked() == linked
                && latestSnapshot.visualProfiles().equals(visualWindow.profiles())
                && latestSnapshot.visualWindowPeriodTicks() == visualWindow.periodTicks();
        if (!sameVersion) {
            if (latestSnapshot == null
                    || !latestSnapshot.visualProfiles().equals(visualWindow.profiles())) {
                RadarMonitorDiagnostics.visualGeometryChanged(serverLevel, this,
                        visualWindow.profiles());
            }
            latestSnapshot = new MonitorSnapshotData(displayId, worldPosition,
                    serverLevel.dimension().location(), loadId, ++snapshotSequence, structureVersion,
                    targetSnapshot.publishedEpoch(), targetSnapshot.revision(), linked,
                    unavailable, chosen, targets, visualWindow.profiles(),
                    visualWindow.periodTicks(), visualWindow.remainingTicks());
            RadarMonitorDiagnostics.snapshotReceived(serverLevel, this, latestSnapshot);
        }

        if (recipient != null) {
            MonitorSnapshotData addressedSnapshot = latestSnapshot.withWindowTiming(
                    visualWindow.periodTicks(), visualWindow.remainingTicks());
            MonitorPayloads.sendToPlayer(recipient, addressedSnapshot, openGui);
            RadarMonitorDiagnostics.snapshotSentAddressed(
                    serverLevel, this, addressedSnapshot, recipient, openGui);
            // Адресная отправка обслуживает только этого игрока и не подтверждает
            // доставку остальным наблюдателям, подписанным на чанки дисплея.
            return;
        }
        if (openGui || hasSnapshotChangedSinceBroadcast()) {
            MonitorPayloads.sendToTracking(serverLevel, latestSnapshot);
            RadarMonitorDiagnostics.snapshotBroadcast(serverLevel, this, latestSnapshot);
            rememberSentVersion();
        }
    }

    /**
     * Собирает только профили зарегистрированных статических радаров текущей сети.
     * Чтение не загружает чанки и не трогает регистрацию; незагруженные BlockEntity и endpoint
     * другого измерения пропускаются. Окно берётся у действующего расписания, а не вычисляется
     * делением gameTime, чтобы визуальный круг совпадал с публикацией ядра.
     */
    private VisualWindow collectVisualWindow(ServerLevel serverLevel) {
        if (networkId == null || connection == null || !isConnectionOpen()) return VisualWindow.EMPTY;
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(serverLevel.getServer());
            long currentTick = manager.currentServerTick();
            List<EndpointKey> endpoints = manager.registeredEndpoints(networkId).stream()
                    .filter(EndpointKey.Static.class::isInstance)
                    .filter(endpoint -> endpoint.dimension().equals(serverLevel.dimension()))
                    .sorted(Comparator.comparing(endpoint -> ((EndpointKey.Static) endpoint).position()))
                    .toList();
            Set<EndpointKey> presentEndpoints = new HashSet<>(endpoints);
            visualProfileFailuresLogged.retainAll(presentEndpoints);

            ArrayList<RadarVisualProfile> profiles = new ArrayList<>();
            ScanWindow activeWindow = null;
            ScanWindow fallbackWindow = null;
            for (EndpointKey endpoint : endpoints) {
                EndpointKey.Static stationary = (EndpointKey.Static) endpoint;
                if (!serverLevel.hasChunkAt(stationary.position())) continue;
                BlockEntity blockEntity = serverLevel.getBlockEntity(stationary.position());
                if (!(blockEntity instanceof RadarVisualProfileSource source)) continue;
                try {
                    if (source.visualNetworkId().filter(networkId::equals).isEmpty()) continue;
                    Optional<RadarVisualProfile> provided = source.radarVisualProfile();
                    if (provided.isEmpty()) continue;
                    RadarVisualProfile profile = provided.orElseThrow();
                    if (!profile.dimension().equals(stationary.dimension().location())
                            || !profile.radarPosition().equals(stationary.position())) {
                        throw new IllegalStateException("Radar visual profile identity does not match its endpoint");
                    }
                    Optional<ScanWindow> scheduled = manager.scanWindowForRadar(stationary);
                    if (scheduled.isEmpty()) continue;
                    profiles.add(profile);
                    ScanWindow window = scheduled.orElseThrow();
                    if (window.eligibleThisWindow()) {
                        if (activeWindow == null || window.boundaryTick() < activeWindow.boundaryTick()) {
                            activeWindow = window;
                        }
                    } else if (fallbackWindow == null || window.boundaryTick() < fallbackWindow.boundaryTick()) {
                        fallbackWindow = window;
                    }
                    visualProfileFailuresLogged.remove(endpoint);
                } catch (RuntimeException exception) {
                    if (visualProfileFailuresLogged.add(endpoint)) {
                        RadarDiagnostics.logException("monitor_visual_profile_read_failed", networkId,
                                endpoint, null, currentTick,
                                "operation=read_profile_or_scan_schedule", exception);
                    }
                }
            }
            profiles.sort(Comparator.comparing(RadarVisualProfile::radarPosition));
            ScanWindow window = activeWindow == null ? fallbackWindow : activeWindow;
            long period = window == null ? 0L : window.periodTicks();
            long remaining = window == null ? 0L
                    : Math.max(0L, Math.min(period, window.boundaryTick() - currentTick));
            visualProfileCollectionFailureLogged = false;
            return new VisualWindow(profiles, period, remaining,
                    window == null ? -1L : window.boundaryTick(), currentTick);
        } catch (RuntimeException exception) {
            if (!visualProfileCollectionFailureLogged) {
                visualProfileCollectionFailureLogged = true;
                RadarDiagnostics.logException("monitor_visual_profile_collection_failed", networkId,
                        new EndpointKey.Static(serverLevel.dimension(), worldPosition), null,
                        serverLevel.getGameTime(), "operation=read_registered_visual_profiles", exception);
            }
            return VisualWindow.EMPTY;
        }
    }

    private record VisualWindow(List<RadarVisualProfile> profiles, long periodTicks, long remainingTicks,
                                long nextBoundaryTick, long observedServerTick) {
        private static final VisualWindow EMPTY = new VisualWindow(List.of(), 0L, 0L, -1L, -1L);

        private VisualWindow {
            profiles = List.copyOf(profiles);
        }
    }

    /**
     * Переводит ближайшую границу часов менеджера в одноразовый дедлайн игровых тиков мира.
     * Часы обоих механизмов продвигаются один раз за серверный тик; разница вычисляется при
     * чтении расписания, поэтому не требуется запрашивать полный снимок на каждом тике.
     * При отсутствии профилей или расписания дедлайн снимается, и остаётся обычный опрос.
     */
    private void scheduleSnapshotRefresh(ServerLevel serverLevel, VisualWindow visualWindow) {
        if (visualWindow.profiles().isEmpty() || visualWindow.periodTicks() <= 0L
                || visualWindow.nextBoundaryTick() < 0L || visualWindow.observedServerTick() < 0L) {
            clearSnapshotRefreshSchedule();
            return;
        }
        long ticksUntilBoundary = Math.max(
                0L, visualWindow.nextBoundaryTick() - visualWindow.observedServerTick());
        nextSnapshotRefreshGameTime = serverLevel.getGameTime() + ticksUntilBoundary;
    }

    /** Сбрасывает runtime-дедлайн при потере связи, корня или расписания. */
    private void clearSnapshotRefreshSchedule() {
        nextSnapshotRefreshGameTime = -1L;
    }

    private boolean isConnectionOpen() {
        if (connection == null) {
            return false;
        }
        try {
            boolean open = connection.isOpen();
            connectionCheckFailureLogged = false;
            return open;
        } catch (RuntimeException exception) {
            if (!connectionCheckFailureLogged) {
                connectionCheckFailureLogged = true;
                ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
                RadarMonitorDiagnostics.connectionCheckFailed(serverLevel, this, exception);
            }
            return false;
        }
    }

    private boolean hasSnapshotChangedSinceBroadcast() {
        return latestSnapshot != null && latestSnapshot.sequence() != lastSentSequence;
    }

    private void rememberSentVersion() {
        lastSentSequence = latestSnapshot.sequence();
    }

    private static MonitorTargetData projectTarget(TargetView view) {
        TargetRef target = view.target();
        LiveResult live = view.liveResult();
        if (!view.detectedThisWindow() || live.position().isEmpty() || live.entityType().isEmpty()) {
            return new MonitorTargetData(target, view.detectedThisWindow(), false, 0, 0, 0, "");
        }
        Vec3 position = live.position().orElseThrow();
        EntityType<?> type = live.entityType().orElseThrow();
        return new MonitorTargetData(target, true, true, position.x, position.y, position.z,
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    /** Подготавливает корень к одному ограниченному проходу геометрии. */
    void beginAssemblyResolution() {
        assemblyDirty = false;
        ensureIdentity();
    }

    /** Помечает панель изменившейся для следующей серверной проверки сборки. */
    void markAssemblyDirty() {
        assemblyDirty = true;
    }

    /** Помечает повёрнутую через Create панель и её корень для повторного серверного резолва. */
    static void panelRotated(Level level, BlockPos position) {
        if (!(level instanceof ServerLevel)
                || !(level.getBlockEntity(position) instanceof RadarDisplayBlockEntity panel)) {
            return;
        }
        panel.markAssemblyDirty();
        panel.setChanged();
        panel.markRootDirtyIfPresent();
        RadarDisplayAssembly.markDirtyAround(level, position, panel.displayId);
    }

    /** Восстанавливает только отсутствующий ID сети из копии соседней панели. */
    void setNetworkIdFromAssembly(NetworkId recoveredNetworkId) {
        if (networkId == null && recoveredNetworkId != null) {
            networkId = recoveredNetworkId;
            latestSnapshot = null;
            setChanged();
        }
    }

    /** Сбрасывает старые данные карты, когда корневой экран меняет геометрию. */
    void invalidateSnapshotForAssemblyChange() {
        latestSnapshot = null;
    }

    void applyAssembly(BlockPos root, UUID id, NetworkId network,
                       BlockPos origin, int width, int height, long version,
                       boolean active, RadarDisplayFrameShape frame) {
        // Если резолвер переназначил старый корень, сначала снимаем регистрацию и фиксируем
        // переход владельца один раз — последующие проходы уже увидят сохранённую новую позицию.
        if (isRoot() && !worldPosition.equals(root)) {
            closeConnection("assembly_reassigned_root");
            if (level instanceof ServerLevel serverLevel) {
                RadarMonitorDiagnostics.rootChanged(serverLevel, this, worldPosition, root,
                        "assembly_reconciliation");
            }
        }
        boolean networkChanged = !java.util.Objects.equals(networkId, network);
        rootPosition = root.immutable();
        displayId = id;
        networkId = network;
        rectangleOrigin = origin.immutable();
        rectangleWidth = width;
        rectangleHeight = height;
        structureVersion = version;
        if (networkChanged) latestSnapshot = null;
        BlockState state = getBlockState();
        if (state.getValue(RadarDisplayBlock.ACTIVE) != active
                || state.getValue(RadarDisplayBlock.FRAME_SHAPE) != frame) {
            level.setBlock(worldPosition,
                    state.setValue(RadarDisplayBlock.ACTIVE, active)
                            .setValue(RadarDisplayBlock.FRAME_SHAPE, frame), Block.UPDATE_ALL);
        }
        setChanged();
        syncClient();
    }

    void markRootDirtyIfPresent() {
        if (level == null || isRoot() || !level.hasChunkAt(rootPosition())) {
            return;
        }
        if (level.getBlockEntity(rootPosition()) instanceof RadarDisplayBlockEntity root
                && root.isRoot() && displayId != null && displayId.equals(root.displayId)) {
            root.assemblyDirty = true;
        }
    }

    /** Переназначает сохранённую принадлежность панели корню, найденному при восстановлении. */
    void setAssemblyRoot(BlockPos root, UUID id, NetworkId network) {
        rootPosition = root.immutable();
        displayId = id;
        networkId = network;
        markAssemblyDirty();
        setChanged();
        syncClient();
    }

    private static void onPanelRemoved(Level level, BlockPos position, RadarDisplayBlockEntity removed) {
        if (!(level instanceof ServerLevel serverLevel) || removed == null || removed.displayId == null) {
            return;
        }
        UUID id = removed.displayId;
        Direction facing = removed.getBlockState().getValue(RadarDisplayBlock.FACING);
        List<RadarDisplayBlockEntity> survivors = RadarDisplayAssembly
                .collectMembers(serverLevel, id, position, facing).stream()
                .filter(panel -> !panel.getBlockPos().equals(position))
                .sorted(Comparator.comparing(RadarDisplayBlockEntity::getBlockPos,
                        RadarDisplayStructureResolver::compareRootPositions))
                .toList();
        if (removed.isRoot() && !survivors.isEmpty()) {
            RadarDisplayBlockEntity newRoot = survivors.getFirst();
            newRoot.promoteToRoot(id, removed.networkId, removed.getBlockPos(), "root_removed");
            for (RadarDisplayBlockEntity panel : survivors) {
                panel.rootPosition = newRoot.worldPosition;
                panel.networkId = removed.networkId;
                panel.markAssemblyDirty();
                panel.setChanged();
                panel.syncClient();
            }
        }
        for (RadarDisplayBlockEntity panel : survivors) {
            panel.markAssemblyDirty();
            panel.markRootDirtyIfPresent();
        }
    }

    void promoteToRoot(UUID id, NetworkId preservedNetworkId,
                       BlockPos previousRoot, String reason) {
        closeConnection("root_promotion");
        displayId = id;
        rootPosition = worldPosition;
        networkId = preservedNetworkId;
        rectangleOrigin = null;
        rectangleWidth = 0;
        rectangleHeight = 0;
        structureVersion++;
        loadId = UUID.randomUUID();
        latestSnapshot = null;
        // Новому владельцу нужно разослать первый снимок всем наблюдателям даже при совпавшей
        // версии сети с прежним корнем; история доставки принадлежит конкретной загрузке корня.
        lastSentSequence = -1L;
        registrationFailureLogged = false;
        connectionCheckFailureLogged = false;
        assemblyDirty = true;
        if (level instanceof ServerLevel serverLevel && !worldPosition.equals(previousRoot)) {
            RadarMonitorDiagnostics.rootChanged(serverLevel, this, previousRoot, worldPosition, reason);
        }
        setChanged();
        syncClient();
    }

    static void panelRemoved(Level level, BlockPos position, BlockEntity blockEntity) {
        if (blockEntity instanceof RadarDisplayBlockEntity display) {
            onPanelRemoved(level, position, display);
        }
    }

    static void panelPlaced(Level level, BlockPos position, BlockEntity blockEntity) {
        if (!(blockEntity instanceof RadarDisplayBlockEntity panel)) return;
        panel.markAssemblyDirty();
        panel.markRootDirtyIfPresent();
        if (panel.displayId != null) {
            RadarDisplayAssembly.markDirtyAround(level, position, panel.displayId);
        }
    }

    private void syncClient() {
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    private void writePersistentData(CompoundTag tag) {
        if (displayId != null) tag.putUUID(DISPLAY_ID_TAG, displayId);
        if (rootPosition != null) tag.putLong(ROOT_POS_TAG, rootPosition.asLong());
        if (networkId != null) tag.putUUID(NETWORK_ID_TAG, networkId.value());
        if (rectangleOrigin != null) tag.putLong(RECT_ORIGIN_TAG, rectangleOrigin.asLong());
        tag.putInt(RECT_WIDTH_TAG, rectangleWidth);
        tag.putInt(RECT_HEIGHT_TAG, rectangleHeight);
        tag.putLong(STRUCTURE_VERSION_TAG, structureVersion);
    }

    /** NetworkId остаётся серверной привязкой; клиенту передаются только данные сборки и рендера. */
    private void writeClientData(CompoundTag tag) {
        if (displayId != null) tag.putUUID(DISPLAY_ID_TAG, displayId);
        if (rootPosition != null) tag.putLong(ROOT_POS_TAG, rootPosition.asLong());
        if (rectangleOrigin != null) tag.putLong(RECT_ORIGIN_TAG, rectangleOrigin.asLong());
        tag.putInt(RECT_WIDTH_TAG, rectangleWidth);
        tag.putInt(RECT_HEIGHT_TAG, rectangleHeight);
        tag.putLong(STRUCTURE_VERSION_TAG, structureVersion);
    }

    private void readPersistentData(CompoundTag tag) {
        displayId = tag.hasUUID(DISPLAY_ID_TAG) ? tag.getUUID(DISPLAY_ID_TAG) : null;
        rootPosition = tag.contains(ROOT_POS_TAG) ? BlockPos.of(tag.getLong(ROOT_POS_TAG)) : null;
        networkId = tag.hasUUID(NETWORK_ID_TAG) ? new NetworkId(tag.getUUID(NETWORK_ID_TAG)) : null;
        rectangleOrigin = tag.contains(RECT_ORIGIN_TAG) ? BlockPos.of(tag.getLong(RECT_ORIGIN_TAG)) : null;
        rectangleWidth = Math.max(0, tag.getInt(RECT_WIDTH_TAG));
        rectangleHeight = Math.max(0, tag.getInt(RECT_HEIGHT_TAG));
        structureVersion = Math.max(0, tag.getLong(STRUCTURE_VERSION_TAG));
    }

}
