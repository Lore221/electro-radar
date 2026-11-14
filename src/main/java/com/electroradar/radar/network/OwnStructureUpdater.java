package com.electroradar.radar.network;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import com.electroradar.radar.spatial.StructureBody;

/** Обновляет собственные структуры один раз на сеть в начале каждого нового серверного тика. */
final class OwnStructureUpdater {
    private OwnStructureUpdater() { }

    static boolean refresh(RadarNetworkState.NetworkRuntime network,
                           Map<EndpointKey, RadarNetworkState.Registration> registrations, long tick,
                           Function<TargetRef, Optional<StructureBody>> lookup) {
        boolean changed = false;
        for (var entry : network.radars.entrySet()) {
            if (!(entry.getKey() instanceof EndpointKey.Moving moving)) continue;
            var registration = registrations.get(moving);
            if (registration == null || registration.role != EndpointRole.RADAR
                    || !registration.networkId.equals(network.id)
                    || !registration.token.equals(entry.getValue().token)) continue;
            var target = new TargetRef(moving.dimension(), TargetRef.SourceKind.MOVING_STRUCTURE, moving.structureId());
            LiveResult previous = network.ownStructures.get(target);
            // Несколько радаров на одной структуре публикуют один sample текущего тика.
            if (previous != null && previous.sampledTick() == tick) continue;
            var body = lookup.apply(target);
            if (body.isEmpty()) continue;
            LiveResult next = LiveResult.observedStructure(tick, body.orElseThrow());
            changed |= !next.sameVisibleState(previous);
            network.ownStructures.put(target, next);
        }
        var samples = network.ownStructures.values().iterator();
        while (samples.hasNext()) {
            if (samples.next().sampledTick() != tick) {
                samples.remove();
                changed = true;
            }
        }
        return changed;
    }
}
