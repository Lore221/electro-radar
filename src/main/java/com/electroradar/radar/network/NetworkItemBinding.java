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
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains(NETWORK_ID_TAG)) return null;
        CompoundTag customData = data.copyTag();
        return customData.hasUUID(NETWORK_ID_TAG)
                ? new NetworkId(customData.getUUID(NETWORK_ID_TAG))
                : null;
    }

    public static NetworkKind kind(ItemStack stack) {
        String kind = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getString("electroradar:network_kind");
        return "interception".equals(kind) ? NetworkKind.INTERCEPTION : NetworkKind.RADAR;
    }

    public static void write(ItemStack stack, NetworkId networkId, NetworkKind kind) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
            data.putUUID(NETWORK_ID_TAG, networkId.value());
            data.putString("electroradar:network_kind", kind == NetworkKind.INTERCEPTION ? "interception" : "radar");
        });
    }

    /** Записывает ID сети, не трогая пользовательские данные других модов. */
    public static void write(ItemStack stack, NetworkId networkId) {
        write(stack, networkId, NetworkKind.RADAR);
    }

    /** Очищает только выбор сети; имя, компоненты и чужие поля CUSTOM_DATA сохраняются. */
    public static void clear(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                customData -> {
                    customData.remove(NETWORK_ID_TAG);
                    customData.remove("electroradar:network_kind");
                });
    }
}
