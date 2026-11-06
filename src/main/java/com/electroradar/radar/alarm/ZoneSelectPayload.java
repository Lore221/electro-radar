package com.electroradar.radar.alarm;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.electroradar.ElectroRadar;

/** Sends intent, never client-supplied world coordinates; the server computes the target. */
public record ZoneSelectPayload(InteractionHand hand, Optional<UUID> selectionId,
        boolean air, int range, boolean clear) implements CustomPacketPayload {
    public static final Type<ZoneSelectPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "zone_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneSelectPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeEnum(value.hand());
                buffer.writeOptional(value.selectionId(), (buf, id) -> buf.writeUUID(id));
                buffer.writeBoolean(value.air());
                buffer.writeVarInt(value.range());
                buffer.writeBoolean(value.clear());
            }, buffer -> new ZoneSelectPayload(buffer.readEnum(InteractionHand.class),
                    buffer.readOptional(buf -> buf.readUUID()), buffer.readBoolean(), buffer.readVarInt(), buffer.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, (value, context) -> context.enqueueWork(() ->
                ZoneSelectionItem.interact(context.player(), value.hand(), value.selectionId(), value.air(), value.range(), value.clear())));
    }
}
