package com.electroradar.radar.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Читает и записывает копируемый ID сети на предметах радара и монитора.
 *
 * <p>Устройства хранят UUID только для восстановления связи после установки. Токен регистрации,
 * опубликованные цели и состояние менеджера в предмет не попадают. Обновление через
 * {@link CustomData#update} меняет только namespaced-ключ Electro Radar и сохраняет прочие поля.</p>
 */
public final class NetworkItemBinding {
    /** Единый ключ для всех предметов, которые можно привязать к радарной сети. */
    public static final String NETWORK_ID_TAG = "electroradar:network_id";

    private NetworkItemBinding() {
    }

    /** Возвращает корректный UUID из ключа Electro Radar либо {@code null}, если предмет не привязан. */
    public static NetworkId read(ItemStack stack) {
        CompoundTag customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return customData.hasUUID(NETWORK_ID_TAG)
                ? new NetworkId(customData.getUUID(NETWORK_ID_TAG))
                : null;
    }

    /** Записывает ID сети, не трогая пользовательские данные других модов. */
    public static void write(ItemStack stack, NetworkId networkId) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                customData -> customData.putUUID(NETWORK_ID_TAG, networkId.value()));
    }
}
