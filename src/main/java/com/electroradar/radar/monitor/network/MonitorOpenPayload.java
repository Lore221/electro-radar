package com.electroradar.radar.monitor.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/** Отдельная серверная команда открыть GUI; поздние payload обновления не открывают экран повторно. */
public record MonitorOpenPayload(UUID displayId, BlockPos rootPosition, ResourceLocation dimension,
                                 UUID loadId, long structureVersion) implements CustomPacketPayload {
    public static final Type<MonitorOpenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorOpenPayload> STREAM_CODEC =
            StreamCodec.of(MonitorOpenPayload::write, MonitorOpenPayload::read);

    public MonitorOpenPayload {
        rootPosition = rootPosition.immutable();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorOpenPayload payload) {
        buffer.writeUUID(payload.displayId);
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.loadId);
        buffer.writeVarLong(payload.structureVersion);
    }

    private static MonitorOpenPayload read(RegistryFriendlyByteBuf buffer) {
        return new MonitorOpenPayload(buffer.readUUID(), buffer.readBlockPos(),
                buffer.readResourceLocation(), buffer.readUUID(), buffer.readVarLong());
    }
}
