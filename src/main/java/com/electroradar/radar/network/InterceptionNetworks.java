package com.electroradar.radar.network;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import com.electroradar.radar.alarm.ShellAlarmBlockEntity;

/** Серверный реестр загруженных сетей; не загружает чанки при разрешении владельца. */
public final class InterceptionNetworks {
    private static final Map<MinecraftServer, InterceptionNetworkDirectory> DIRECTORIES = new IdentityHashMap<>();
    private InterceptionNetworks() { }
    public static InterceptionNetworkDirectory forServer(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Interception networks are server-thread only");
        return DIRECTORIES.computeIfAbsent(server, ignored -> new InterceptionNetworkDirectory());
    }
    public static ShellAlarmBlockEntity owner(ServerLevel level, NetworkId network) {
        if (network == null) return null;
        var endpoint = forServer(level.getServer()).owner(network);
        if (endpoint == null || !endpoint.dimension().equals(level.dimension()) || !level.hasChunkAt(endpoint.position())) return null;
        return level.getBlockEntity(endpoint.position()) instanceof ShellAlarmBlockEntity alarm
                && network.equals(alarm.interceptionNetworkId()) ? alarm : null;
    }
    public static void onServerStopped(ServerStoppedEvent event) { DIRECTORIES.remove(event.getServer()); }
}
