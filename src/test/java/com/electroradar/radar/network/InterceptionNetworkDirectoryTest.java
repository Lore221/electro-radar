package com.electroradar.radar.network;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.*;

class InterceptionNetworkDirectoryTest {
    private static EndpointKey.Static endpoint(int x) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
        return new EndpointKey.Static(dimension, new BlockPos(x, 0, 0));
    }
    @Test void networksExposeOnlyTheirOwnOwnerAndControllers() {
        var directory = new InterceptionNetworkDirectory();
        var a = NetworkId.create(); var b = NetworkId.create();
        directory.registerOwner(a, endpoint(1)); directory.registerOwner(b, endpoint(2));
        directory.connect(endpoint(3), a);
        assertEquals(endpoint(1), directory.owner(a));
        assertEquals(java.util.Set.of(endpoint(1), endpoint(3)), java.util.Set.copyOf(directory.members(a)));
        assertEquals(java.util.List.of(endpoint(2)), directory.members(b));
        directory.connect(endpoint(3), b);
        assertFalse(directory.members(a).contains(endpoint(3)));
    }
    @Test void secondOwnerOfTheSameNetworkIsRefusedWithoutReplacingTheFirst() {
        var directory = new InterceptionNetworkDirectory(); var network = NetworkId.create();
        assertTrue(directory.registerOwner(network, endpoint(1)));
        assertTrue(directory.registerOwner(network, endpoint(1)));
        assertFalse(directory.registerOwner(network, endpoint(2)));
        assertEquals(endpoint(1), directory.owner(network));
    }
    @Test void unloadingOwnerRevokesMembershipAndCannotRemoveAnotherOwner() {
        var directory = new InterceptionNetworkDirectory(); var network = NetworkId.create();
        assertFalse(directory.connect(endpoint(2), network));
        directory.registerOwner(network, endpoint(1)); directory.connect(endpoint(2), network);
        directory.removeOwner(network, endpoint(9));
        assertEquals(endpoint(1), directory.owner(network));
        directory.removeOwner(network, endpoint(1));
        assertNull(directory.owner(network)); assertTrue(directory.members(network).isEmpty());
        directory.registerOwner(network, endpoint(1));
        assertEquals(java.util.List.of(endpoint(1)), directory.members(network));
    }
}
