package com.electroradar.radar.controller;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/** S2C-снимок локальной анимации Create entity: текущий угол, цель и оставшиеся тики. */
public record RadarPanelTiltPayload(int entityId, float currentAngle, float targetAngle,
                                    int remainingTicks) implements CustomPacketPayload {
    public static final Type<RadarPanelTiltPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "radar_panel_tilt"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarPanelTiltPayload> STREAM_CODEC =
            StreamCodec.of(RadarPanelTiltPayload::write, RadarPanelTiltPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, RadarPanelTiltPayload payload) {
        buffer.writeVarInt(payload.entityId());
        buffer.writeFloat(payload.currentAngle());
        buffer.writeFloat(payload.targetAngle());
        buffer.writeVarInt(payload.remainingTicks());
    }

    private static RadarPanelTiltPayload read(RegistryFriendlyByteBuf buffer) {
        return new RadarPanelTiltPayload(buffer.readVarInt(), buffer.readFloat(),
                buffer.readFloat(), buffer.readVarInt());
    }
}
