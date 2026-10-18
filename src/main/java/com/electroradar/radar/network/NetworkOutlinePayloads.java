package com.electroradar.radar.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Регистрация S2C-пакета подсветки без ссылок на клиентский рендер. */
public final class NetworkOutlinePayloads {
    private NetworkOutlinePayloads() {
    }

    /**
     * Dedicated server объявляет исходящий тип пакета со служебным обработчиком, как и другие
     * серверные S2C-типы проекта. На физическом клиенте настоящий обработчик устанавливает
     * NetworkOutlineClientEvents; общий класс никогда не загружает Minecraft или Catnip Outliner.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToClient(NetworkOutlinePayload.TYPE, NetworkOutlinePayload.STREAM_CODEC,
                    (payload, context) -> { });
        }
    }
}
