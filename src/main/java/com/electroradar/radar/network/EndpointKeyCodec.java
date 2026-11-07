package com.electroradar.radar.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

/** Shared bounded endpoint wire format for monitor identity and commands. */
public final class EndpointKeyCodec {
    private EndpointKeyCodec() { }
    public static void write(RegistryFriendlyByteBuf b, EndpointKey key) {
        b.writeResourceLocation(key.dimension().location());
        b.writeBoolean(key instanceof EndpointKey.Moving);
        if (key instanceof EndpointKey.Moving moving) {
            b.writeUUID(moving.structureId()); b.writeBlockPos(moving.localPosition());
        } else b.writeBlockPos(((EndpointKey.Static) key).position());
    }
    public static EndpointKey read(RegistryFriendlyByteBuf b) {
        var dimension = ResourceKey.create(Registries.DIMENSION, b.readResourceLocation());
        return b.readBoolean() ? new EndpointKey.Moving(dimension, b.readUUID(), b.readBlockPos())
                : new EndpointKey.Static(dimension, b.readBlockPos());
    }
}
