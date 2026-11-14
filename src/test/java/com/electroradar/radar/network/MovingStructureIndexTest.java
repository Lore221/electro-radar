package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MovingStructureIndexTest {
    @Test void indexContainsOnlyStructuresAndRemovesThemAfterTheLastSource() {
        var world=ResourceKey.create(Registries.DIMENSION,ResourceLocation.withDefaultNamespace("overworld"));
        var network=new RadarNetworkState.NetworkRuntime(new NetworkId(new UUID(0,1)));
        var structure=new TargetRef(world,TargetRef.SourceKind.MOVING_STRUCTURE,new UUID(0,2));
        var entity=new TargetRef(world,TargetRef.SourceKind.ENTITY,structure.id());
        var entry=network.getOrCreateTarget(structure);
        assertSame(entry,network.getOrCreateTarget(structure));
        network.getOrCreateTarget(entity);
        assertEquals(Set.of(structure),network.movingStructures);
        var first=new EndpointKey.Static(world,BlockPos.ZERO);
        var second=new EndpointKey.Static(world,new BlockPos(1,0,0));
        var a=new RadarNetworkState.SourceLink(); var b=new RadarNetworkState.SourceLink();
        entry.sources.put(first,a); entry.sources.put(second,b);
        network.removeTargetSource(first,structure,a);
        assertEquals(Set.of(structure),network.movingStructures);
        network.removeTargetSource(second,structure,new RadarNetworkState.SourceLink());
        assertEquals(Set.of(structure),network.movingStructures,"A stale source link cannot remove a new one");
        network.removeTargetSource(second,structure,b);
        assertTrue(network.movingStructures.isEmpty());
        assertTrue(network.targets.containsKey(entity));
    }
}
