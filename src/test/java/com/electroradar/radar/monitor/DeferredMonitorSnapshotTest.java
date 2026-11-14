package com.electroradar.radar.monitor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import com.electroradar.radar.network.*;
import com.electroradar.radar.spatial.SilhouetteTestFixtures;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import org.junit.jupiter.api.Test;

class DeferredMonitorSnapshotTest {
    @Test void snapshotComparisonAndPacketPreparationStayLazyWithoutViewers() {
        var dimension = ResourceKey.create(Registries.DIMENSION,ResourceLocation.withDefaultNamespace("overworld"));
        var ref = new TargetRef(dimension,TargetRef.SourceKind.MOVING_STRUCTURE,new UUID(0,1));
        var silhouette = SilhouetteTestFixtures.deferred(() -> { fail("No viewer requested geometry"); return null; });
        var first = new MonitorTargetData(ref,true,true,0,0,0,"",-1,0,silhouette);
        var second = new MonitorTargetData(ref,true,true,0,0,0,"",-1,0,silhouette);
        var endpoint = new EndpointKey.Static(dimension,BlockPos.ZERO);
        var a = new MonitorSnapshotData(new UUID(0,2),BlockPos.ZERO,dimension.location(),new UUID(0,3),1,0,1,
                true,false,null,List.of(first),List.of(),List.of(),endpoint);
        var b = new MonitorSnapshotData(a.displayId(),BlockPos.ZERO,dimension.location(),a.loadId(),2,0,2,
                true,false,null,List.of(second),List.of(),List.of(),endpoint);
        assertTrue(a.sameContent(b));
        assertNotNull(MonitorSnapshotPayload.prepare(a.targets(),List.of(),List.of()));
    }
}
