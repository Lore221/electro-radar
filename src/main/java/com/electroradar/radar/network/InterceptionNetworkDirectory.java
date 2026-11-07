package com.electroradar.radar.network;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Состав загруженных сетей перехвата; не регистрирует приборы в радарной сети. */
public final class InterceptionNetworkDirectory {
    private final Map<NetworkId, EndpointKey.Static> owners = new HashMap<>();
    private final Map<EndpointKey.Static, NetworkId> controllers = new HashMap<>();

    public void registerOwner(NetworkId network, EndpointKey.Static owner) {
        EndpointKey.Static previous = owners.putIfAbsent(network, owner);
        if (previous != null && !previous.equals(owner)) throw new IllegalStateException("Interception network already owned");
    }

    public EndpointKey.Static owner(NetworkId network) { return owners.get(network); }

    public boolean connect(EndpointKey.Static controller, NetworkId network) {
        disconnect(controller);
        if (!owners.containsKey(network)) return false;
        controllers.put(controller, network);
        return true;
    }

    public void disconnect(EndpointKey.Static controller) { controllers.remove(controller); }

    public void removeOwner(NetworkId network, EndpointKey.Static owner) {
        if (!owners.remove(network, owner)) return;
        controllers.values().removeIf(network::equals);
    }

    public List<EndpointKey> members(NetworkId network) {
        EndpointKey.Static owner = owners.get(network);
        if (owner == null) return List.of();
        LinkedHashSet<EndpointKey> result = new LinkedHashSet<>();
        result.add(owner);
        controllers.forEach((endpoint, bound) -> { if (network.equals(bound)) result.add(endpoint); });
        return List.copyOf(result);
    }
}
