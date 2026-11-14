package com.electroradar.radar.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import com.electroradar.radar.spatial.StructureBody;

/** Общий результат подготовки геометрии для всех сетей одной серверной фазы. */
final class StructureGeometryPhase {
    private final Map<TargetRef, Optional<StructureBody>> bodies = new HashMap<>();
    private final Function<TargetRef, Optional<StructureBody>> load;

    StructureGeometryPhase(Function<TargetRef, Optional<StructureBody>> load) { this.load = load; }

    Optional<StructureBody> get(TargetRef target) { return bodies.computeIfAbsent(target, load); }
}
