package com.electroradar.radar.monitor.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/** Запрос полного baseline конкретного экрана; произвольные UUID цели протокол не принимает. */
public record MonitorResyncRequestPayload(BlockPos rootPosition, ResourceLocation dimension,
                                          UUID displayId, UUID loadId,
                                          long knownSequence, long knownViewGeneration)
        implements CustomPacketPayload {
    public static final Type<MonitorResyncRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_resync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorResyncRequestPayload> STREAM_CODEC =
            StreamCodec.of(MonitorResyncRequestPayload::write, MonitorResyncRequestPayload::read);

    public MonitorResyncRequestPayload {
        rootPosition = rootPosition.immutable();
        if (knownSequence < 0 || knownViewGeneration < 0) {
            throw new IllegalArgumentException("Invalid monitor resync version");
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorResyncRequestPayload payload) {
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.displayId);
        buffer.writeUUID(payload.loadId);
        buffer.writeVarLong(payload.knownSequence);
        buffer.writeVarLong(payload.knownViewGeneration);
    }

    private static MonitorResyncRequestPayload read(RegistryFriendlyByteBuf buffer) {
        return new MonitorResyncRequestPayload(buffer.readBlockPos(), buffer.readResourceLocation(),
                buffer.readUUID(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong());
    }
}
