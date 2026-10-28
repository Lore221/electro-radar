package com.electroradar.radar.monitor.network;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.RadarMonitorDiagnostics;

/**
 * Регистрация пакетов и серверная маршрутизация снимков монитора.
 *
 * <p>Открытие GUI — отдельный пакет только перед адресным снимком. Рассылка уходит только
 * игрокам, наблюдающим чанк корня. Новый наблюдатель получает адресный снимок с геометрией
 * в момент передачи ему чанка, поэтому восстановление по запросу клиента не нужно.</p>
 */
public final class MonitorPayloads {
    public static final String PROTOCOL_VERSION = "7";

    private MonitorPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(MonitorSelectPayload.TYPE, MonitorSelectPayload.STREAM_CODEC,
                MonitorPayloads::handleSelection);
        registrar.playToServer(MonitorGeometryRequestPayload.TYPE, MonitorGeometryRequestPayload.STREAM_CODEC,
                MonitorPayloads::handleGeometryRequest);
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            // Dedicated server регистрирует исходящие типы без ссылки на клиентские классы.
            registrar.playToClient(MonitorSnapshotPayload.TYPE, MonitorSnapshotPayload.STREAM_CODEC,
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
        for (var blockEntity : List.copyOf(event.getChunk().getBlockEntities().values())) {
            if (blockEntity instanceof RadarDisplayBlockEntity display && display.isRoot()) {
                display.sendTo(player);
            }
        }
    }

    /** Адресный снимок всегда несёт геометрию: у получателя её может ещё не быть. */
    public static void sendToPlayer(ServerPlayer player, MonitorSnapshotData snapshot, boolean openGui) {
        if (openGui) {
            PacketDistributor.sendToPlayer(player, new MonitorOpenPayload(snapshot.displayId(),
                    snapshot.rootPosition(), snapshot.dimension(), snapshot.loadId()));
        }
        for (MonitorSnapshotPayload part : MonitorSnapshotPayload.split(snapshot, true)) {
            PacketDistributor.sendToPlayer(player, part);
        }
    }

    /** Рассылает снимок наблюдателям чанка корня; геометрию — только когда она изменилась. */
    public static void sendToTracking(ServerLevel level, MonitorSnapshotData snapshot, boolean includeGeometry) {
        ChunkPos chunk = new ChunkPos(snapshot.rootPosition());
        for (MonitorSnapshotPayload part : MonitorSnapshotPayload.split(snapshot, includeGeometry)) {
            PacketDistributor.sendToPlayersTrackingChunk(level, chunk, part);
        }
    }

    /** Проверки идентичности, наблюдения чанка и частоты выполняет сам корень. Чанк не загружается. */
    private static void handleGeometryRequest(MonitorGeometryRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())
                || !level.hasChunkAt(payload.rootPosition())) return;
        if (level.getBlockEntity(payload.rootPosition()) instanceof RadarDisplayBlockEntity root) {
            root.handleGeometryRequest(player, payload.displayId(), payload.loadId());
        }
    }

    private static void handleSelection(MonitorSelectPayload payload, IPayloadContext context) {
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
}
