package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/**
 * Компактный серверный список загруженных статических участников сети для одного игрока.
 * В payload входят только позиции и registry ID типа BlockEntity: токены и состояние сети
 * остаются на сервере, а геометрию клиент получает из уже загруженного мира.
 */
public record NetworkOutlinePayload(ResourceLocation dimension, NetworkId networkId,
                                    long sequence, List<Entry> entries, boolean truncated)
        implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 512;
    public static final Type<NetworkOutlinePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "network_outline"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkOutlinePayload> STREAM_CODEC =
            StreamCodec.of(NetworkOutlinePayload::write, NetworkOutlinePayload::read);

    public NetworkOutlinePayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(networkId, "networkId");
        entries = List.copyOf(entries);
        if (sequence < 0 || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid network outline payload bounds");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, NetworkOutlinePayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.networkId.value());
        buffer.writeVarLong(payload.sequence);
        buffer.writeBoolean(payload.truncated);
        buffer.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buffer.writeBlockPos(entry.position);
            buffer.writeResourceLocation(entry.blockEntityTypeId);
        }
    }

    private static NetworkOutlinePayload read(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        NetworkId networkId = new NetworkId(buffer.readUUID());
        long sequence = buffer.readVarLong();
        boolean truncated = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Network outline payload exceeds its entry limit");
        }
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(buffer.readBlockPos(), buffer.readResourceLocation()));
        }
        return new NetworkOutlinePayload(dimension, networkId, sequence, entries, truncated);
    }

    /** Один endpoint; BlockPos и registry ID проверяются повторно клиентом перед отрисовкой. */
    public record Entry(BlockPos position, ResourceLocation blockEntityTypeId) {
        public Entry {
            position = Objects.requireNonNull(position, "position").immutable();
            Objects.requireNonNull(blockEntityTypeId, "blockEntityTypeId");
        }
    }
}
