package com.electroradar.radar.monitor;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.TargetRef;

/**
 * Формирует события жизненного цикла монитора и передаёт их в общий журнал радара.
 *
 * <p>Здесь нет ticker и состояния: вызывающий серверный код сообщает только о переходе,
 * принятом/отклонённом действии или ошибке. Поэтому класс не пишет в журнал каждый тик,
 * для каждой цели или для каждого сетевого фрагмента снимка.</p>
 */
public final class RadarMonitorDiagnostics {
    private RadarMonitorDiagnostics() {
    }

    /** Фиксирует появление новой идентичности монитора на сервере. */
    static void displayCreated(ServerLevel level, RadarDisplayBlockEntity display, String source) {
        event(level, display, "monitor_created", null,
                "source=" + source + " root=" + display.getBlockPos());
    }

    /** Фиксирует единственную смену координаты корня и причину передачи владения. */
    static void rootChanged(ServerLevel level, RadarDisplayBlockEntity display,
                            BlockPos oldRoot, BlockPos newRoot, String reason) {
        event(level, display, "monitor_root_changed", null,
                "oldRoot=" + oldRoot + " newRoot=" + newRoot + " reason=" + reason);
    }

    /** Фиксирует изменение прямоугольника или набора активных панелей после резолва. */
    static void assemblyChanged(ServerLevel level, RadarDisplayBlockEntity display,
                                BlockPos oldOrigin, int oldWidth, int oldHeight,
                                BlockPos newOrigin, int newWidth, int newHeight,
                                boolean compositionChanged) {
        event(level, display, "monitor_assembly_changed", null,
                "oldOrigin=" + oldOrigin + " oldSize=" + oldWidth + "x" + oldHeight
                        + " newOrigin=" + newOrigin + " newSize=" + newWidth + "x" + newHeight
                        + " rectangleArea=" + (newWidth * newHeight)
                        + " compositionChanged=" + compositionChanged);
    }

    /** Фиксирует успешное создание runtime-связи потребителя. */
    static void consumerConnectionOpened(ServerLevel level, RadarDisplayBlockEntity display) {
        event(level, display, "monitor_consumer_connection_opened", null,
                "role=MONITOR endpoint=" + endpoint(level, display.getBlockPos()));
    }

    /** Фиксирует снятие runtime-связи и причину, вызвавшую закрытие. */
    static void consumerConnectionClosed(ServerLevel level, RadarDisplayBlockEntity display,
                                         String reason, boolean closed) {
        String details = "displayId=" + display.displayId() + " root=" + display.getBlockPos()
                + " reason=" + reason + " result=" + (closed ? "closed" : "close_failed");
        if (level == null) {
            RadarDiagnostics.log("monitor_consumer_connection_closed", display.networkId(), null,
                    null, null, details);
        } else {
            event(level, display, "monitor_consumer_connection_closed", null,
                    "reason=" + reason + " result=" + (closed ? "closed" : "close_failed"));
        }
    }

    /** Записывает исключение проверки актуальности ConsumerConnection. */
    static void connectionCheckFailed(ServerLevel level, RadarDisplayBlockEntity display,
                                      RuntimeException exception) {
        RadarDiagnostics.logException("monitor_consumer_connection_check_failed",
                display.networkId(), level == null ? null : endpoint(level, display.getBlockPos()), null,
                level == null ? null : level.getGameTime(),
                "displayId=" + display.displayId() + " root=" + display.getBlockPos()
                        + " operation=ConsumerConnection.isOpen", exception);
    }

    /** Записывает исключение регистрации новой ConsumerConnection. */
    static void registrationFailed(ServerLevel level, RadarDisplayBlockEntity display,
                                   String operation, RuntimeException exception) {
        RadarDiagnostics.logException("monitor_registration_failed", display.networkId(),
                endpoint(level, display.getBlockPos()), null, level.getGameTime(), operation, exception);
    }

    /** Записывает исключение снятия токена связи; закрытие не маскирует ошибку менеджера. */
    static void connectionCloseFailed(ServerLevel level, RadarDisplayBlockEntity display,
                                      String reason, RuntimeException exception) {
        RadarDiagnostics.logException("monitor_consumer_connection_close_failed",
                display.networkId(), level == null ? null : endpoint(level, display.getBlockPos()), null,
                level == null ? null : level.getGameTime(),
                "displayId=" + display.displayId() + " root=" + display.getBlockPos()
                        + " reason=" + reason + " operation=ConsumerConnection.close", exception);
    }

    /** Фиксирует только новый снимок и считает свежие/помнимые записи одним проходом. */
    static void snapshotReceived(ServerLevel level, RadarDisplayBlockEntity display,
                                 MonitorSnapshotData snapshot) {
        long freshTargets = snapshot.targets().stream()
                .filter(MonitorTargetData::detectedThisWindow).count();
        long rememberedTargets = snapshot.targets().size() - freshTargets;
        event(level, display, "monitor_snapshot_received", snapshot.publishedEpoch(),
                "revision=" + snapshot.revision() + " freshTargets=" + freshTargets
                        + " rememberedTargets=" + rememberedTargets
                        + " totalTargets=" + snapshot.targets().size()
                        + " structureVersion=" + snapshot.structureVersion()
                        + " linked=" + snapshot.linked());
    }

    /** Фиксирует одну адресную серию частей, не отдельное сообщение на каждый фрагмент. */
    static void snapshotSentAddressed(ServerLevel level, RadarDisplayBlockEntity display,
                                      MonitorSnapshotData snapshot, ServerPlayer player,
                                      boolean openGui) {
        event(level, display, "monitor_snapshot_sent_addressed", snapshot.publishedEpoch(),
                "revision=" + snapshot.revision() + " recipient=" + player.getUUID()
                        + " openGui=" + openGui + " targets=" + snapshot.targets().size());
    }

    /** Фиксирует одну рассылку наблюдателям чанка после изменения версии снимка. */
    static void snapshotBroadcast(ServerLevel level, RadarDisplayBlockEntity display,
                                  MonitorSnapshotData snapshot) {
        event(level, display, "monitor_snapshot_broadcast", snapshot.publishedEpoch(),
                "revision=" + snapshot.revision() + " targets=" + snapshot.targets().size());
    }

    /** Фиксирует результат принятой сервером команды выбора или снятия выбора. */
    static void selectionAccepted(ServerLevel level, RadarDisplayBlockEntity display,
                                  ServerPlayer player, TargetRef target) {
        event(level, display, "monitor_target_selection_accepted", null,
                "player=" + player.getUUID() + " " + target(target)
                        + " action=" + (target == null ? "clear" : "select"));
    }

    /** Фиксирует отказ с конкретным первым условием, остановившим обработку команды. */
    public static void selectionRejected(ServerLevel level, RadarDisplayBlockEntity display,
                                         BlockPos rootPosition, UUID requestedDisplayId,
                                         ServerPlayer player, TargetRef target, String reason) {
        NetworkId networkId = display == null ? null : display.networkId();
        UUID actualDisplayId = display == null ? null : display.displayId();
        RadarDiagnostics.log("monitor_target_selection_rejected", networkId,
                level == null ? null : endpoint(level, rootPosition), null,
                level == null ? null : level.getGameTime(),
                "root=" + rootPosition + " displayId=" + actualDisplayId
                        + " requestedDisplayId=" + requestedDisplayId
                        + " player=" + (player == null ? "-" : player.getUUID())
                        + " " + target(target) + " reason=" + reason);
    }

    /** Сохраняет stack trace, если сетевой менеджер бросил исключение при применении выбора. */
    static void selectionFailed(ServerLevel level, RadarDisplayBlockEntity display,
                                ServerPlayer player, TargetRef target,
                                RuntimeException exception) {
        RadarDiagnostics.logException("monitor_target_selection_failed", display.networkId(),
                endpoint(level, display.getBlockPos()), null, level.getGameTime(),
                "player=" + player.getUUID() + " " + target(target), exception);
    }

    private static void event(ServerLevel level, RadarDisplayBlockEntity display,
                              String name, Object epoch, String details) {
        RadarDiagnostics.log(name, display.networkId(), endpoint(level, display.getBlockPos()),
                epoch, level.getGameTime(), "displayId=" + display.displayId() + " " + details);
    }

    private static EndpointKey.Static endpoint(ServerLevel level, BlockPos position) {
        return new EndpointKey.Static(level.dimension(), position);
    }

    private static String target(TargetRef target) {
        return target == null ? "target=-" : "target=" + target.dimension().location()
                + "/" + target.sourceKind() + "/" + target.id();
    }
}
