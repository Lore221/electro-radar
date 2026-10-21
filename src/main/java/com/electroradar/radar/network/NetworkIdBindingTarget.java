package com.electroradar.radar.network;

/**
 * Узкий серверный контракт устройства, которое хранит собственную идентичность сети.
 * Предметы читают и передают только NetworkId; runtime token и результаты сканов не копируются.
 */
public interface NetworkIdBindingTarget {
    NetworkId getOrCreateNetworkIdForBinding();

    void applyNetworkIdFromItem(NetworkId networkId);
}
