package com.electroradar.radar.monitor.network;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/**
 * Запрос клиента прислать геометрию экрана, которой у него нет.
 *
 * <p>Клиент шлёт его, когда получил полный снимок без профилей, а нужной {@code geometryVersion}
 * в его кэше нет: например, серия с геометрией пришла раньше, чем загрузился корень. Сервер
 * отвечает обычным адресным снимком, который всегда несёт геометрию.</p>
 */
public record MonitorGeometryRequestPayload(BlockPos rootPosition, ResourceLocation dimension,
                                            UUID displayId, UUID loadId, long geometryVersion)
        implements CustomPacketPayload {
    public static final Type<MonitorGeometryRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_geometry_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorGeometryRequestPayload> STREAM_CODEC =
            StreamCodec.of(MonitorGeometryRequestPayload::write, MonitorGeometryRequestPayload::read);

    public MonitorGeometryRequestPayload {
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(loadId, "loadId");
        if (geometryVersion < 0) throw new IllegalArgumentException("Geometry version must be non-negative");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorGeometryRequestPayload payload) {
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeUUID(payload.displayId);
        buffer.writeUUID(payload.loadId);
        buffer.writeVarLong(payload.geometryVersion);
    }

    private static MonitorGeometryRequestPayload read(RegistryFriendlyByteBuf buffer) {
        return new MonitorGeometryRequestPayload(buffer.readBlockPos(), buffer.readResourceLocation(),
                buffer.readUUID(), buffer.readUUID(), buffer.readVarLong());
    }
}
