package com.electroradar.radar.network;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkBindingPersistenceTest {
    @BeforeAll static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }
    @Test void interceptionBindingSurvivesSavingAndClearPreservesOtherItemData() {
        var stack = new ItemStack(Items.PAPER);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("marker", "kept"));
        var id = NetworkId.create();
        NetworkItemBinding.write(stack, id, NetworkKind.INTERCEPTION);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var restored = ItemStack.parseOptional(registries, (CompoundTag) stack.save(registries));
        assertEquals(id, NetworkItemBinding.read(restored));
        assertEquals(NetworkKind.INTERCEPTION, NetworkItemBinding.kind(restored));
        NetworkItemBinding.clear(restored);
        assertNull(NetworkItemBinding.read(restored));
        assertEquals("kept", restored.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker"));
    }
    @Test void oldBindingIsRadarAndRadarCopyReplacesInterceptionType() {
        var stack = new ItemStack(Items.PAPER); var id = NetworkId.create();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(NetworkItemBinding.NETWORK_ID_TAG, id.value()));
        assertEquals(NetworkKind.RADAR, NetworkItemBinding.kind(stack));
        NetworkItemBinding.write(stack, id, NetworkKind.INTERCEPTION);
        NetworkItemBinding.write(stack, id);
        assertEquals(NetworkKind.RADAR, NetworkItemBinding.kind(stack));
    }
    @Test void outlineWireRoundTripRetainsNetworkDomain() {
        var payload = new NetworkOutlinePayload(ResourceLocation.parse("minecraft:overworld"), NetworkId.create(),
                5, java.util.List.of(), false, NetworkKind.INTERCEPTION);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            NetworkOutlinePayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, NetworkOutlinePayload.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
