package com.electroradar.radar.alarm;

import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.electroradar.ElectroRadar;

/** Клиент просит сдвинуть одну грань на блок; координаты и ItemStack клиент не присылает. */
public record ZoneResizePayload(InteractionHand hand, UUID selectionId, Direction face, int step) implements CustomPacketPayload {
    public static final Type<ZoneResizePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "zone_resize"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneResizePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.hand());
                buffer.writeUUID(payload.selectionId());
                buffer.writeEnum(payload.face());
                buffer.writeByte(payload.step());
            }, buffer -> new ZoneResizePayload(buffer.readEnum(InteractionHand.class), buffer.readUUID(), buffer.readEnum(Direction.class), buffer.readByte()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> ZoneSelectionItem.resize(context.player(), payload.hand(), payload.selectionId(), payload.face(), payload.step())));
    }
}
