package com.electroradar.radar.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.EndpointRole;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.TargetRef;

/**
 * Сохраняет идентичность одной панели, а у панели-корня дополнительно владеет единственной
 * runtime-связью с радарной сетью и последним готовым экранным снимком.
 *
 * <p>Все обращения к RadarNetworkManager происходят в серверном ticker или обработчике payload,
 * то есть на основном потоке мира. DisplayId, корень и NetworkId копируются на панели только
 * для восстановления физической сборки и связи после удаления корня; токен регистрации и цели
 * никогда не записываются в NBT. Связь закрывается до удаления, выгрузки и повышения нового корня.</p>
 */
public final class RadarDisplayBlockEntity extends BlockEntity implements NetworkLinkTarget {
    private static final String DISPLAY_ID_TAG = "electroradar_display_id";
    private static final String ROOT_POS_TAG = "electroradar_root_pos";
    private static final String NETWORK_ID_TAG = "electroradar_network_id";
    private static final String RECT_ORIGIN_TAG = "electroradar_rect_origin";
    private static final String RECT_WIDTH_TAG = "electroradar_rect_width";
    private static final String RECT_HEIGHT_TAG = "electroradar_rect_height";
    private static final String STRUCTURE_VERSION_TAG = "electroradar_structure_version";
    private static final String LOAD_ID_TAG = "electroradar_load_id";
    private final MonitorSnapshotRuntime snapshots = new MonitorSnapshotRuntime();

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

    public RadarDisplayBlockEntity(BlockPos position, BlockState state) {
        super(RadarDisplayContent.RADAR_DISPLAY_ENTITY.get(), position, state);
    }

    /** При загрузке новая runtime-связь создаётся позднее на первом серверном тике. */
    @Override
    public void onLoad() {
        super.onLoad();
        connection = null;
        registrationFailureLogged = false;
        connectionCheckFailureLogged = false;
        snapshots.resetForLoad();
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
        snapshots.invalidate();
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
        // Данные готовятся в Pre, поэтому корень читает и публикует единый итог каждый тик.
        display.refreshAndBroadcast(false, null);
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

    /** Любая панель коммутируется через загруженный корень, включая неактивные края сборки. */
    @Override
    public RadarDisplayBlockEntity loadedLinkTarget() {
        if (!(level instanceof ServerLevel)) return null;
        ensureIdentity();
        return loadedRoot();
    }

    @Override
    public NetworkId networkIdForLinking() {
        return networkId;
    }

    /** Смена сети не меняет DisplayId, форму, loadId или монотонный sequence снимков. */
    @Override
    public boolean reconfigureNetwork(NetworkId replacement) {
        if (!(level instanceof ServerLevel)) {
            throw new IllegalStateException("Monitor NetworkId can only be changed on the logical server");
        }
        RadarDisplayBlockEntity root = loadedLinkTarget();
        if (root == null) return false;
        if (root != this) return root.reconfigureNetwork(replacement);
        if (java.util.Objects.equals(networkId, replacement)) return true;
        if (!RadarDisplayAssembly.reconfigureNetwork(this, replacement)) return false;
        refreshAndBroadcast(false, null);
        return true;
    }

    /** Все панели получают новую сохранённую привязку до следующего восстановления корня. */
    void applyNetworkIdFromLinker(NetworkId replacement) {
        closeConnection("linker_reconfiguration");
        networkId = replacement;
        snapshots.invalidate();
        assemblyDirty = true;
        setChanged();
        syncClient();
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
                    root.snapshots.invalidate();
                    root.setChanged();
                }
            }
        }
        rectangleOrigin = member.rectangleOrigin;
        rectangleWidth = member.rectangleWidth;
        rectangleHeight = member.rectangleHeight;
        structureVersion = Math.max(structureVersion, member.structureVersion);
        assemblyDirty = true;
        snapshots.invalidate();
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
            snapshots.invalidate();
        }
    }

    private void refreshAndBroadcast(boolean openGui, ServerPlayer recipient) {
        if (!(level instanceof ServerLevel serverLevel) || !isRoot()) return;
        ensureConnection(serverLevel);
        snapshots.refreshAndBroadcast(this, serverLevel,
                isConnectionOpen() ? connection : null, openGui, recipient);
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
            snapshots.invalidate();
            setChanged();
        }
    }

    /** Сбрасывает старые данные карты, когда корневой экран меняет геометрию. */
    void invalidateSnapshotForAssemblyChange() {
        snapshots.invalidate();
    }

    void applyAssembly(BlockPos root, UUID id, NetworkId network,
                       BlockPos origin, int width, int height, long version,
                       boolean active, RadarDisplayFrameShape frame) {
        closeConnectionBeforeRootReassignment(root, "assembly_reconciliation");
        boolean networkChanged = !java.util.Objects.equals(networkId, network);
        rootPosition = root.immutable();
        displayId = id;
        networkId = network;
        rectangleOrigin = origin.immutable();
        rectangleWidth = width;
        rectangleHeight = height;
        structureVersion = version;
        if (networkChanged) snapshots.invalidate();
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

    /** Снимает регистрацию прежнего корня до записи его новой принадлежности. */
    private void closeConnectionBeforeRootReassignment(BlockPos root, String reason) {
        if (isRoot() && !worldPosition.equals(root)) {
            closeConnection("assembly_reassigned_root");
            if (level instanceof ServerLevel serverLevel) {
                RadarMonitorDiagnostics.rootChanged(serverLevel, this, worldPosition, root, reason);
            }
        }
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
        closeConnectionBeforeRootReassignment(root, "assembly_recovery");
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
                panel.setAssemblyRoot(newRoot.worldPosition, id, removed.networkId);
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
        // История доставки и sequence принадлежат конкретной загрузке корня.
        snapshots.resetForLoad();
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
