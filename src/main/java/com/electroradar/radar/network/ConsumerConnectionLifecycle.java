package com.electroradar.radar.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.electroradar.radar.RadarDiagnostics;

/**
 * Управляет связью загруженного потребителя; после отказа повторяет регистрацию на следующем тике.
 * Сохранённый ID сети и сброс состояния целей остаются ответственностью прибора.
 */
public final class ConsumerConnectionLifecycle implements AutoCloseable {
    private final String registrationFailureEvent;
    private ConsumerConnection current;
    private boolean registrationFailureLogged;

    public ConsumerConnectionLifecycle(String registrationFailureEvent) {
        this.registrationFailureEvent = registrationFailureEvent;
    }

    /** Текущая связь, либо null без привязки или после отказа регистрации. */
    public ConsumerConnection current() {
        return current;
    }

    /** Вызывается на серверном потоке; владелец закрывает связь перед сменой сети или выгрузкой. */
    public void ensure(ServerLevel level, BlockPos position, NetworkId networkId) {
        if (networkId == null) {
            close();
            return;
        }
        if (current != null && current.isOpen()) return;
        close();
        EndpointKey endpoint = new EndpointKey.Static(level.dimension(), position);
        try {
            current = new ConsumerConnection(level.getServer(), endpoint, networkId, EndpointRole.CONSUMER);
            registrationFailureLogged = false;
        } catch (RuntimeException exception) {
            // Повторяем на следующем тике; до успешной регистрации пишем отказ один раз.
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarDiagnostics.logException(registrationFailureEvent, networkId, endpoint,
                        null, level.getGameTime(), null, exception);
            }
        }
    }

    @Override
    public void close() {
        ConsumerConnection previous = current;
        current = null;
        if (previous != null) previous.close();
    }
}
