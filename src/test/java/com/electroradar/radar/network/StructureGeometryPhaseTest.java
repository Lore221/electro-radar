package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StructureGeometryPhaseTest {
    @Test void knownAndOwnStructuresUseOneLookupButPublishPerNetwork() {
        var first=new OwnStructureUpdaterTest.Fixture();
        var second=new OwnStructureUpdaterTest.Fixture(3);
        AtomicInteger reads=new AtomicInteger();
        var phase=new StructureGeometryPhase(target->{reads.incrementAndGet();return Optional.of(first.body);});
        phase.get(first.target);
        assertTrue(OwnStructureUpdater.refresh(first.network,first.endpoints,10,phase::get));
        assertTrue(OwnStructureUpdater.refresh(second.network,second.endpoints,10,phase::get));
        assertEquals(1,reads.get());
        assertEquals(10,first.network.ownStructures.get(first.target).sampledTick());
        assertEquals(10,second.network.ownStructures.get(second.target).sampledTick());
    }

    @Test void missingGeometryIsCachedOnlyForTheCurrentPhase() {
        var f=new OwnStructureUpdaterTest.Fixture();
        AtomicInteger reads=new AtomicInteger();
        var phase=new StructureGeometryPhase(target->{reads.incrementAndGet();return Optional.empty();});
        assertTrue(phase.get(f.target).isEmpty());
        assertTrue(phase.get(f.target).isEmpty());
        assertEquals(1,reads.get());
        var next=new StructureGeometryPhase(target->{reads.incrementAndGet();return Optional.of(f.body);});
        assertTrue(next.get(f.target).isPresent());
        assertEquals(2,reads.get());
    }

    @Test void sameUuidInDifferentDimensionsHasSeparateGeometry() {
        var f=new OwnStructureUpdaterTest.Fixture();
        var nether=ResourceKey.create(Registries.DIMENSION,ResourceLocation.withDefaultNamespace("the_nether"));
        var other=new TargetRef(nether,TargetRef.SourceKind.MOVING_STRUCTURE,f.target.id());
        AtomicInteger reads=new AtomicInteger();
        var phase=new StructureGeometryPhase(target->{reads.incrementAndGet();return Optional.empty();});
        phase.get(f.target);phase.get(other);phase.get(f.target);
        assertEquals(2,reads.get());
    }
}
