package com.electroradar.radar.network;

/**
 * Серверный контракт источника сети для стандартных сетевых предметов.
 * Любой новый радар подключает его независимо от класса блока. NetworkBoundBlockItem
 * автоматически копирует NetworkId по ПКМ и передаёт его установленному источнику до
 * первой регистрации. Runtime token и результаты сканов в предмет не копируются.
 */
public interface NetworkIdBindingTarget extends NetworkLinkTarget {
    NetworkId getOrCreateNetworkIdForBinding();

    void applyNetworkIdFromItem(NetworkId networkId);

    /** Источник автоматически поддерживает чтение сети коммутатором через тот же контракт. */
    @Override
    default NetworkId networkIdForLinking() {
        return getOrCreateNetworkIdForBinding();
    }
}
