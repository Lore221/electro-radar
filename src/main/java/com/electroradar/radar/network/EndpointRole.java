package com.electroradar.radar.network;

/** Роль endpoint, определяющая разрешённые серверные операции сети. */
public enum EndpointRole {
    RADAR,
    CONSUMER,
    MONITOR;

    /** Возвращает, может ли роль запрашивать сведения о сети и наблюдениях. */
    public boolean canQueryNetwork() {
        return this == CONSUMER || this == MONITOR;
    }

    /** Возвращает, может ли endpoint изменять ручной выбор цели сети. */
    public boolean canSelectTarget() {
        return this == MONITOR;
    }
}
