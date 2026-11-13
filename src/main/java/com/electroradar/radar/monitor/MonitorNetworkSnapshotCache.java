package com.electroradar.radar.monitor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.network.ConsumerConnection;
import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.network.TargetSnapshot;
import com.electroradar.radar.network.TargetView;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Reuses the immutable monitor view shared by connections to one network and dimension.
 * Monitor access depends on network and dimension, while display selection is applied per root.
 */
final class MonitorNetworkSnapshotCache {
    private static final int MAX_ENTRIES_PER_SERVER = 128;
    private static final Object LOCK = new Object();
    private static final Map<MinecraftServer, LinkedHashMap<CacheKey, CacheEntry>> SERVERS = new WeakHashMap<>();

    private MonitorNetworkSnapshotCache() { }

    static Snapshot get(ServerLevel level, NetworkId networkId, BlockPos monitorPosition,
                       ConsumerConnection connection, long contentVersion,
                       MonitorVisualProfileCollector visualProfiles) {
        if (connection == null || networkId == null || contentVersion < 0) return Snapshot.EMPTY;

        MinecraftServer server = level.getServer();
        CacheKey key = new CacheKey(networkId, level.dimension());
        synchronized (LOCK) {
            CacheEntry cached = entriesFor(server).get(key);
            if (cached != null && cached.contentVersion() == contentVersion) return cached.snapshot();
        }

        TargetSnapshot<MonitorTargetData> targets = connection.queryTargets(MonitorNetworkSnapshotCache::projectTarget);
        MonitorVisualProfileCollector.VisualWindow visual = visualProfiles.collect(
                level, networkId, monitorPosition);
        Snapshot collected = new Snapshot(targets.contentVersion(), targets.targets(),
                visual.profiles(), visual.phases());
        if (targets.contentVersion() != contentVersion) return collected;

        synchronized (LOCK) {
            LinkedHashMap<CacheKey, CacheEntry> entries = entriesFor(server);
            CacheEntry latest = entries.get(key);
            if (latest != null && latest.contentVersion() == contentVersion) return latest.snapshot();
            entries.put(key, new CacheEntry(contentVersion, collected));
            while (entries.size() > MAX_ENTRIES_PER_SERVER) {
                CacheKey eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }
        return collected;
    }

    private static LinkedHashMap<CacheKey, CacheEntry> entriesFor(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new LinkedHashMap<>(16, 0.75f, true));
    }

    private static MonitorTargetData projectTarget(TargetView view) {
        TargetRef target = view.target();
        LiveResult live = view.liveResult();
        if (live.structure().isPresent() && live.position().isPresent()) {
            Vec3 point = live.position().get();
            return new MonitorTargetData(target, live.observing(), true, point.x, point.y, point.z, "",
                    live.freezeTick(), live.fadeDurationTicks(), live.structure().get().silhouette(),
                    view.registeredStructure(), live.structure().get().scanAnchor());
        }
        if (live.position().isEmpty() || live.entityType().isEmpty()) {
            return new MonitorTargetData(target, false, false, 0, 0, 0, "",
                    live.freezeTick(), live.fadeDurationTicks());
        }
        Vec3 position = live.position().orElseThrow();
        EntityType<?> type = live.entityType().orElseThrow();
        return new MonitorTargetData(target, live.observing(), true, position.x, position.y, position.z,
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), live.freezeTick(), live.fadeDurationTicks());
    }

    record Snapshot(long contentVersion, List<MonitorTargetData> targets,
                    List<RadarVisualProfile> visualProfiles, List<MonitorVisualPhase> visualPhases) {
        private static final Snapshot EMPTY = new Snapshot(-1L, List.of(), List.of(), List.of());

        Snapshot {
            targets = List.copyOf(targets);
            visualProfiles = List.copyOf(visualProfiles);
            visualPhases = List.copyOf(visualPhases);
        }
    }

    private record CacheKey(NetworkId networkId, ResourceKey<Level> dimension) { }
    private record CacheEntry(long contentVersion, Snapshot snapshot) { }
}
