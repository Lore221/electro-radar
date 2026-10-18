package com.electroradar.client.networkoutline;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.network.NetworkOutlinePayload;

/** Все ссылки на Minecraft-клиент и Catnip Outliner зарегистрированы только на физическом клиенте. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class NetworkOutlineClientEvents {
    private NetworkOutlineClientEvents() {
    }

    /** Обработчик выполняет изменение состояния только после перехода на основной клиентский поток. */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(NetworkOutlinePayload.TYPE, NetworkOutlinePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> NetworkOutlineClientState.accept(payload)));
    }

    /** Проверка рук, загруженных блоков и обновление сроков жизни контуров выполняются один раз за тик. */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        NetworkOutlineClientState.tick();
    }
}
