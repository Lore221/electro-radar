package com.electroradar.radar.monitor.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.network.TargetRef;

/** Запрос GUI выбрать свежую цель или очистить общий ручной выбор сети. */
public record MonitorSelectPayload(BlockPos rootPosition, UUID displayId, TargetRef target)
        implements CustomPacketPayload {
    public static final Type<MonitorSelectPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorSelectPayload> STREAM_CODEC =
            StreamCodec.of(MonitorSelectPayload::write, MonitorSelectPayload::read);

    public MonitorSelectPayload {
        rootPosition = rootPosition.immutable();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, MonitorSelectPayload payload) {
        buffer.writeBlockPos(payload.rootPosition);
        buffer.writeUUID(payload.displayId);
        MonitorSnapshotPayload.writeTargetRef(buffer, payload.target);
    }

    private static MonitorSelectPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos root = buffer.readBlockPos();
        UUID displayId = buffer.readUUID();
        TargetRef target = MonitorSnapshotPayload.readTargetRef(buffer);
        return new MonitorSelectPayload(root, displayId, target);
    }
}
