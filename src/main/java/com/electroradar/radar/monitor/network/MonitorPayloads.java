package com.electroradar.radar.monitor.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.RadarMonitorDiagnostics;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;

/**
 * Регистрация пакетов и серверная маршрутизация снимков монитора.
 *
 * <p>Открытие GUI — отдельный признак только у первого адресного снимка. Части обычных обновлений
 * никогда не создают экран. Снимки поверхности уходят только игрокам, наблюдающим чанк корня;
 * полный серверный список не рассылается всем игрокам.</p>
 */
public final class MonitorPayloads {
    public static final String PROTOCOL_VERSION = "3";
    private static final java.util.Map<java.util.UUID, Long> LAST_RESYNC_REQUEST = new java.util.HashMap<>();
    private MonitorPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(MonitorSelectPayload.TYPE, MonitorSelectPayload.STREAM_CODEC,
                MonitorPayloads::handleSelection);
        registrar.playToServer(MonitorResyncRequestPayload.TYPE, MonitorResyncRequestPayload.STREAM_CODEC,
                MonitorPayloads::handleResyncRequest);
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            // Dedicated server регистрирует исходящие типы без ссылки на клиентские классы.
            registrar.playToClient(MonitorSnapshotPayload.TYPE, MonitorSnapshotPayload.STREAM_CODEC,
                    (payload, context) -> { });
            registrar.playToClient(MonitorDeltaPayload.TYPE, MonitorDeltaPayload.STREAM_CODEC,
                    (payload, context) -> { });
            registrar.playToClient(MonitorOpenPayload.TYPE, MonitorOpenPayload.STREAM_CODEC,
                    (payload, context) -> { });
        }
    }

    /** Отправляет актуальные снимки корней, содержащихся в только что переданном клиенту чанке. */
    public static void onChunkWatched(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        // sendTo может пересобрать конструкцию и вызвать setBlock в этом же чанке, поэтому
        // обходим копию, а не живую карту BlockEntity чанка.
        for (var blockEntity : java.util.List.copyOf(event.getChunk().getBlockEntities().values())) {
            if (blockEntity instanceof RadarDisplayBlockEntity display && display.isRoot()) {
                display.sendTo(player);
            }
        }
    }

    /** Разбивает снимок на ограниченные части и отправляет конкретному игроку. */
    public static void sendToPlayer(ServerPlayer player, MonitorSnapshotData snapshot, boolean openGui) {
        if (openGui) {
            PacketDistributor.sendToPlayer(player, new MonitorOpenPayload(snapshot.displayId(),
                    snapshot.rootPosition(), snapshot.dimension(), snapshot.loadId(), snapshot.structureVersion()));
        }
        for (MonitorSnapshotPayload part : MonitorSnapshotPayload.split(snapshot)) {
            PacketDistributor.sendToPlayer(player, part);
        }
    }

    /** Рассылает снимок только наблюдателям чанка корня. */
    public static void sendToTracking(ServerLevel level, MonitorSnapshotData snapshot) {
        for (MonitorSnapshotPayload part : MonitorSnapshotPayload.split(snapshot)) {
            PacketDistributor.sendToPlayersTrackingChunk(level,
                    new net.minecraft.world.level.ChunkPos(snapshot.rootPosition()), part);
        }
    }

    /** Рассылает один абсолютный delta интервал только наблюдателям чанка корня. */
    public static void sendDeltaToTracking(ServerLevel level, MonitorSnapshotData snapshot, long fromSequence,
                                           java.util.List<com.electroradar.radar.monitor.MonitorTargetData> upserts,
                                           java.util.List<com.electroradar.radar.network.TargetRef> removes) {
        for (MonitorDeltaPayload part : MonitorDeltaPayload.split(snapshot, fromSequence, upserts, removes)) {
            PacketDistributor.sendToPlayersTrackingChunk(level,
                    new net.minecraft.world.level.ChunkPos(snapshot.rootPosition()), part);
        }
    }

    private static void handleSelection(MonitorSelectPayload payload,
                                        net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(payload.rootPosition())) {
            RadarMonitorDiagnostics.selectionRejected(level, null, payload.rootPosition(),
                    payload.displayId(), player, payload.target(), "root_chunk_not_loaded");
            return;
        }
        if (level.getBlockEntity(payload.rootPosition()) instanceof RadarDisplayBlockEntity root) {
            root.handleSelection(player, payload.displayId(), payload.target());
        } else {
            RadarMonitorDiagnostics.selectionRejected(level, null, payload.rootPosition(),
                    payload.displayId(), player, payload.target(), "root_block_entity_missing");
        }
    }

    private static void handleResyncRequest(MonitorResyncRequestPayload payload,
                                            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || !level.hasChunkAt(payload.rootPosition())) return;
        long now = level.getGameTime();
        Long previous = LAST_RESYNC_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < 20L) return;
        LAST_RESYNC_REQUEST.put(player.getUUID(), now);
        LAST_RESYNC_REQUEST.entrySet().removeIf(entry -> now - entry.getValue() > 1_200L);
        if (LAST_RESYNC_REQUEST.size() > 4_096) {
            LAST_RESYNC_REQUEST.clear();
            LAST_RESYNC_REQUEST.put(player.getUUID(), now);
        }
        if (level.getBlockEntity(payload.rootPosition()) instanceof RadarDisplayBlockEntity root) {
            root.handleResyncRequest(player, payload.displayId(), payload.loadId());
        }
    }
}
