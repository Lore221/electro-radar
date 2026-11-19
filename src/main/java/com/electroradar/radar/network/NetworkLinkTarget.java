package com.electroradar.radar.network;

/**
 * Серверный контракт установленного устройства для коммутатора сетей.
 * Реализация сама закрывает прежнюю регистрацию и сохраняет новую привязку; предмет
 * не получает токены, таблицы целей или доступ к внутренностям сетевого менеджера.
 */
public interface NetworkLinkTarget {
    /** Возвращает загруженного владельца привязки; {@code null}, если корень сборки недоступен. */
    default NetworkLinkTarget loadedLinkTarget() {
        return this;
    }

    default NetworkKind networkKindForLinking() { return NetworkKind.RADAR; }

    /** Общие потребители поддерживают обе сети наблюдения; источники сужают этот контракт. */
    default boolean acceptsNetworkKind(NetworkKind kind) {
        return kind == networkKindForLinking()
                || networkKindForLinking() == NetworkKind.RADAR && kind == NetworkKind.SIMPLE_RADAR;
    }

    /** Источник создаёт собственную сеть при необходимости; непривязанный потребитель возвращает null. */
    NetworkId networkIdForLinking();

    /**
     * Применяет выбранную сеть; null отключает потребителя или выделяет источнику новую сеть.
     * Возвращает false без изменений, если не все необходимые части сборки загружены.
     */
    boolean reconfigureNetwork(NetworkId networkId);
}
