package com.electroradar.radar.network;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Состав загруженных сетей перехвата; не регистрирует приборы в радарной сети. */
public final class InterceptionNetworkDirectory {
    private final Map<NetworkId, EndpointKey.Static> owners = new HashMap<>();
    private final Map<EndpointKey.Static, NetworkId> controllers = new HashMap<>();

    /**
     * @return false, если сетью уже владеет другой прибор: тогда владелец не меняется. Копия
     *         прибора с тем же ID (pick-block с NBT, вставка схемы) должна завести себе новую сеть.
     */
    public boolean registerOwner(NetworkId network, EndpointKey.Static owner) {
        EndpointKey.Static previous = owners.putIfAbsent(network, owner);
        return previous == null || previous.equals(owner);
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
