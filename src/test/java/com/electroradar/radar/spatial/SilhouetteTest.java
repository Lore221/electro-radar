package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import org.junit.jupiter.api.Test;

class SilhouetteTest {
    @Test void comparisonsDoNotBuildButValueEqualityAndWireAccessResolveOnce() {
        var calls = new AtomicInteger();
        var quad = new Silhouette.Quad(new Point(0,0),new Point(4,0),new Point(4,4),new Point(0,4));
        var eager = new Silhouette(List.of(quad),List.of(),false);
        var lazy = Silhouette.deferred(() -> { calls.incrementAndGet(); return eager; });
        assertTrue(lazy.sameView(lazy));
        assertFalse(lazy.sameView(eager));
        assertFalse(eager.sameView(lazy));
        assertEquals(0,calls.get());
        assertEquals(eager,lazy);
        assertEquals(lazy,eager);
        assertEquals(eager.hashCode(),lazy.hashCode());
        assertEquals(eager.fills(),lazy.fills());
        assertEquals(eager.outline(),lazy.outline());
        assertFalse(lazy.detailLimited());
        assertTrue(lazy.sameView(eager));
        assertEquals(1,calls.get());
    }
}
