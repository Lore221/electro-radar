package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import com.electroradar.radar.spatial.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DeferredStructureMeasurementTest {
    @Test void contentVersionComparisonDoesNotRequestScreenGeometry() {
        var silhouette = SilhouetteTestFixtures.deferred(() -> { fail("Core comparison must not project"); return null; });
        var first = new StructureMeasurement("ship",silhouette,Vec3.ZERO,Vec3.ZERO,1);
        var second = new StructureMeasurement("ship",silhouette,Vec3.ZERO,Vec3.ZERO,1);
        var a = new LiveResult(1,TargetAvailability.AVAILABLE,Optional.of(Vec3.ZERO),Optional.empty(),true,-1,0,Optional.of(first));
        var b = new LiveResult(2,TargetAvailability.AVAILABLE,Optional.of(Vec3.ZERO),Optional.empty(),true,-1,0,Optional.of(second));
        assertTrue(a.sameVisibleState(b));
        var distinct = SilhouetteTestFixtures.deferred(() -> { fail("Core comparison must not project"); return null; });
        assertFalse(first.sameVisibleState(new StructureMeasurement("ship",distinct,Vec3.ZERO,Vec3.ZERO,1)));
        assertSame(silhouette,a.frozen(3,100).structure().orElseThrow().silhouette());
    }
}
