package com.electroradar.radar.debug;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkPolicy;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.RegistrationToken;
import com.electroradar.radar.network.ScanWindow;
import com.electroradar.radar.scan.AabbRadarScanArea;
import com.electroradar.radar.scan.EntityRadarScanner;

/**
 * Серверное состояние одного установленного тестового радара.
 *
 * <p>BlockEntity сохраняет только собственный NetworkId. Токен регистрации и отметка уже
 * предпринятого epoch существуют только в памяти этой загрузки: менеджер владеет связями
 * обнаружений, а повторная загрузка должна получить новый токен и заново запросить расписание.
 * Все поиск сущностей и сетевые вызовы выполняются на серверном потоке; клиентский экземпляр
 * не создаёт ID и не отправляет отчёты.</p>
 */
public final class DebugRadarBlockEntity extends BlockEntity {
    private static final String NETWORK_ID_TAG = "NetworkId";

    /** Настройки именно временного блока; общий EntityRadarScanner их не знает. */
    private static final long PERIOD_TICKS = 5;
    private static final long FORGET_AFTER_MISSES = 3;
    private static final double AREA_SIZE_X = 100.0;
    private static final double AREA_SIZE_Y = 30.0;
    private static final double AREA_SIZE_Z = 100.0;
    private static final NetworkPolicy POLICY = new NetworkPolicy(PERIOD_TICKS, FORGET_AFTER_MISSES);
    private static final Predicate<Entity> ELIGIBLE_ENTITY = entity -> !(entity instanceof ItemEntity);
    private static final EntityRadarScanner SCANNER = new EntityRadarScanner();

    /** Единственное сохраняемое значение идентичности этого экземпляра радара. */
    private NetworkId networkId;

    /** Остаток runtime-регистрации; не сериализуется и не переносится между загрузками чанка. */
    private RegistrationToken registrationToken;
    private EndpointKey.Static endpoint;

    /** Epoch, в котором уже начат проход; присваивается до скана, чтобы не повторить частичный проход. */
    private Long lastAttemptedEpoch;
    private Long lastNotEligibleEpoch;
    private boolean loadedNetworkId;

    /** Подавляют повтор одного и того же сбоя в каждом тике; сбрасываются после восстановления. */
    private boolean registrationFailureLogged;
    private boolean managerUnavailableLogged;
    private boolean noScheduleLogged;

    public DebugRadarBlockEntity(BlockPos position, BlockState state) {
        super(DebugRadarContent.DEBUG_RADAR_ENTITY.get(), position, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // onLoad может произойти до того, как BlockItem передаст ID после super.place().
        // Поэтому здесь нельзя создавать ID или регистрировать радар: это делает первый server tick.
        registrationToken = null;
        endpoint = null;
        lastAttemptedEpoch = null;
        lastNotEligibleEpoch = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Не сохраняем токен, расписание и цели: при загрузке их заново выдаёт сетевой менеджер.
        if (networkId != null) {
            tag.putUUID(NETWORK_ID_TAG, networkId.value());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Отсутствующий или старый ID будет создан на сервере в ensureNetworkId().
        if (tag.hasUUID(NETWORK_ID_TAG)) {
            networkId = new NetworkId(tag.getUUID(NETWORK_ID_TAG));
            loadedNetworkId = true;
        } else {
            networkId = null;
            loadedNetworkId = false;
        }
        registrationToken = null;
        endpoint = null;
        lastAttemptedEpoch = null;
        lastNotEligibleEpoch = null;
    }

    @Override
    public void setRemoved() {
        // Этот путь вызывается и при удалении блока, и при снятии BlockEntity с выгружаемого чанка.
        unregister("block_entity_removed");
        super.setRemoved();
    }

    /**
     * Вызывается блоковым тикером на стороне мира. Только здесь обычная установка без привязки
     * получает новый ID и радар регистрируется. Отложенный запуск оставляет BlockItem время
     * передать ID из ItemStack после успешного vanilla-размещения, но до первого сетевого вызова.
     */
    public static void serverTick(Level level, BlockPos position, BlockState state, DebugRadarBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        blockEntity.ensureNetworkId();
        blockEntity.register(serverLevel);
        blockEntity.scanCurrentWindow(serverLevel);
    }

    /**
     * Оставляет ID из NBT без изменений или создаёт его для впервые поставленного блока.
     * Создание выполняется только на сервере; loadedNetworkId нужен лишь для однократного
     * различимого сообщения о восстановлении уже сохранённой идентичности.
     */
    private void ensureNetworkId() {
        if (networkId == null) {
            networkId = NetworkId.create();
            loadedNetworkId = false;
            // Помечаем чанк грязным сразу: UUID должен пережить выгрузку даже до следующего autosave.
            setChanged();
            RadarDiagnostics.log("network_id_created", networkId, endpoint, null, currentTick(),
                    "reason=missing_saved_id");
            return;
        }
        if (loadedNetworkId) {
            RadarDiagnostics.log("network_id_loaded", networkId, endpoint, null, currentTick(),
                    "source=block_entity_nbt");
            loadedNetworkId = false;
        }
    }

    /**
     * Возвращает идентификатор радара для привязки предмета. Метод вызывается только серверным
     * DebugRadarItem; если игрок успел нажать на блок до его первого тика, ID создаётся и блок
     * сразу помечается изменённым, чтобы значение сохранилось в NBT.
     */
    NetworkId getOrCreateNetworkIdForBinding() {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("A debug radar network can only be read on the server");
        }
        endpoint = new EndpointKey.Static(serverLevel.dimension(), worldPosition);
        ensureNetworkId();
        return networkId;
    }

    /**
     * Принимает только копируемую идентичность сети из предмета и сохраняет её до первой регистрации.
     * Токен, расписание и результаты обнаружений сюда не передаются. Наличие токена означало бы,
     * что нарушен порядок размещения, поэтому такую замену нельзя выполнять молча.
     */
    void applyNetworkIdFromItem(NetworkId itemNetworkId) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("A debug radar network can only be applied on the server");
        }
        if (registrationToken != null) {
            throw new IllegalStateException("Cannot change a debug radar network after registration");
        }
        networkId = itemNetworkId;
        loadedNetworkId = false;
        endpoint = new EndpointKey.Static(serverLevel.dimension(), worldPosition);
        lastAttemptedEpoch = null;
        lastNotEligibleEpoch = null;
        setChanged();
        RadarDiagnostics.log("radar_item_network_id_applied", networkId, endpoint, null,
                currentTick(), "source=debug_radar_item");
    }

    /**
     * Регистрирует этот endpoint один раз на текущую загрузку чанка.
     * При временной ошибке попытка повторяется на следующем серверном тике, но одинаковая
     * ошибка не спамит лог. Выданный менеджером token хранится только до unregister().
     */
    private void register(ServerLevel serverLevel) {
        if (registrationToken != null || networkId == null) {
            return;
        }
        // Endpoint стабилен для стационарного блока; новый token ограничивает полномочия этой загрузки.
        endpoint = new EndpointKey.Static(serverLevel.dimension(), worldPosition);
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(serverLevel.getServer());
            registrationToken = manager.registerRadar(endpoint, networkId, POLICY);
            registrationFailureLogged = false;
            long tick = manager.currentServerTick();
            RadarDiagnostics.log("radar_registration_ready", networkId, endpoint, null, tick,
                    "token=" + registrationToken.value() + " policy=" + POLICY);
        } catch (RuntimeException exception) {
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarDiagnostics.logException("radar_registration_failed", networkId, endpoint, null,
                        currentTick(), "operation=registerRadar", exception);
            }
        }
    }

    /**
     * Запрашивает сетевое расписание и выполняет максимум один полный проход на допустимый epoch.
     * Период не вычисляется через gameTime: границы назначает RadarNetworkManager. Проверка
     * {@code tick < boundaryTick} выполняется до и после поиска, чтобы опоздавший или частичный
     * проход не был выдан за полный отчёт. Ошибку попытки этот экземпляр не повторяет до следующего окна.
     */
    private void scanCurrentWindow(ServerLevel serverLevel) {
        if (registrationToken == null || endpoint == null || networkId == null) {
            return;
        }
        RadarNetworkManager manager;
        try {
            manager = RadarNetworkManager.forServer(serverLevel.getServer());
            managerUnavailableLogged = false;
        } catch (RuntimeException exception) {
            if (!managerUnavailableLogged) {
                managerUnavailableLogged = true;
                RadarDiagnostics.logException("scan_skipped", networkId, endpoint, null, currentTick(),
                        "reason=network_manager_unavailable", exception);
            }
            return;
        }

        long tick = manager.currentServerTick();
        Optional<ScanWindow> scheduled = manager.scanWindowForRadar(endpoint);
        if (scheduled.isEmpty()) {
            if (!noScheduleLogged) {
                noScheduleLogged = true;
                RadarDiagnostics.log("scan_skipped", networkId, endpoint, null, tick,
                        "reason=no_schedule_for_registration");
            }
            return;
        }
        noScheduleLogged = false;

        ScanWindow window = scheduled.get();
        // После загрузки отметка ещё пуста; сравнение с epoch допустимо только после проверки на null.
        if (!window.eligibleThisWindow()
                && (lastNotEligibleEpoch == null || lastNotEligibleEpoch != window.epoch())) {
            lastNotEligibleEpoch = window.epoch();
            RadarDiagnostics.log("scan_skipped", networkId, endpoint, window.epoch(), tick,
                    "reason=not_eligible boundaryTick=" + window.boundaryTick());
        }
        if (!window.eligibleThisWindow() || (lastAttemptedEpoch != null && lastAttemptedEpoch == window.epoch())) {
            return;
        }
        // При границе epoch отчёт уже опоздал; не запускаем тяжёлый поиск в закрытом окне.
        if (tick >= window.boundaryTick()) {
            lastAttemptedEpoch = window.epoch();
            RadarDiagnostics.log("scan_skipped", networkId, endpoint, window.epoch(), tick,
                    "reason=window_boundary_passed boundaryTick=" + window.boundaryTick());
            return;
        }

        // Отмечаем попытку до выполнения. Ошибку логируем, но частичный результат в этом epoch не повторяем.
        lastAttemptedEpoch = window.epoch();
        AabbRadarScanArea area = AabbRadarScanArea.centered(
                Vec3.atCenterOf(worldPosition), AREA_SIZE_X, AREA_SIZE_Y, AREA_SIZE_Z);
        RadarDiagnostics.log("scan_started", networkId, endpoint, window.epoch(), tick,
                "boundaryTick=" + window.boundaryTick() + " sizeX=" + AREA_SIZE_X
                        + " sizeY=" + AREA_SIZE_Y + " sizeZ=" + AREA_SIZE_Z);
        long startedAt = System.nanoTime();
        EntityRadarScanner.ScanResult result;
        try {
            result = SCANNER.scan(serverLevel, area, ELIGIBLE_ENTITY);
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("scan_failed", networkId, endpoint, window.epoch(),
                    currentTick(), "durationNanos=" + (System.nanoTime() - startedAt), exception);
            return;
        }

        long finishedTick = manager.currentServerTick();
        long durationNanos = System.nanoTime() - startedAt;
        logFoundTargets(window.epoch(), finishedTick, result, durationNanos);
        // Сканирование считается полным, только если оно завершилось до границы того же окна.
        if (finishedTick >= window.boundaryTick()) {
            RadarDiagnostics.log("report_rejected", networkId, endpoint, window.epoch(), finishedTick,
                    "reason=window_boundary_passed found=" + result.detections().size());
            return;
        }

        try {
            boolean accepted = manager.submitCompletedScan(
                    endpoint, networkId, registrationToken, window.epoch(), result.detections());
            RadarDiagnostics.log(accepted ? "report_submitted" : "report_rejected",
                    networkId, endpoint, window.epoch(), manager.currentServerTick(),
                    "accepted=" + accepted + " found=" + result.detections().size());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("report_submission_failed", networkId, endpoint, window.epoch(),
                    currentTick(), "found=" + result.detections().size(), exception);
        }
    }

    /** Пишет счётчики и полный набор TargetRef; форматирование UUID списка отключается вместе с диагностикой. */
    private void logFoundTargets(
            long epoch,
            long tick,
            EntityRadarScanner.ScanResult result,
            long durationNanos
    ) {
        // UUID списка целей не форматируются, если общий подробный журнал выключен.
        String foundRefs = RadarDiagnostics.enabled()
                ? result.detections().stream()
                        .map(detection -> detection.target().toString())
                        .collect(Collectors.joining(",", "[", "]"))
                : "[]";
        RadarDiagnostics.log("scan_completed", networkId, endpoint, epoch, tick,
                "candidates=" + result.candidateCount()
                        + " acceptedHitboxes=" + result.acceptedHitboxCount()
                        + " found=" + result.detections().size()
                        + " foundRefs=" + foundRefs
                        + " durationNanos=" + durationNanos);
    }

    /**
     * Снимает только собственную текущую регистрацию по паре NetworkId и token.
     * Повторный вызов безопасен: отсутствие runtime token означает, что снимать нечего.
     */
    private void unregister(String reason) {
        if (registrationToken == null || networkId == null || endpoint == null
                || !(level instanceof ServerLevel serverLevel)) {
            registrationToken = null;
            endpoint = null;
            return;
        }
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(serverLevel.getServer());
            long tick = manager.currentServerTick();
            boolean removed = manager.unregister(endpoint, networkId, registrationToken);
            RadarDiagnostics.log(removed ? "radar_unregistered" : "radar_unregister_rejected",
                    networkId, endpoint, null, tick,
                    "removed=" + removed + " reason=" + reason + " token=" + registrationToken.value());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("radar_unregister_failed", networkId, endpoint, null,
                    currentTick(), "reason=" + reason, exception);
        } finally {
            registrationToken = null;
            endpoint = null;
        }
    }

    /** Нужен для ошибок жизненного цикла, когда менеджер уже недоступен; тогда пишет sentinel -1. */
    long currentTickForDiagnostics() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return -1;
        }
        try {
            return RadarNetworkManager.forServer(serverLevel.getServer()).currentServerTick();
        } catch (RuntimeException ignored) {
            // Это лишь best-effort поле контекста: ошибка здесь не должна скрыть основную
            // причину сбоя или создавать отдельный повторяющийся лог на каждом тике.
            return -1;
        }
    }

    private long currentTick() {
        return currentTickForDiagnostics();
    }

}
