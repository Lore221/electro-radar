package com.electroradar.radar.spatial;

import java.util.function.Supplier;

public final class SilhouetteTestFixtures {
    private SilhouetteTestFixtures() { }
    public static Silhouette deferred(Supplier<Silhouette> projection) { return Silhouette.deferred(projection); }
}
