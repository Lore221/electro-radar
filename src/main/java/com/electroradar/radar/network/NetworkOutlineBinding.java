package com.electroradar.radar.network;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Общая проверка предмета-активатора для сервера и клиента. */
public final class NetworkOutlineBinding {
    private NetworkOutlineBinding() {
    }

    /**
     * Находит сеть подходящего предмета в руках игрока: основная рука имеет приоритет,
     * а вторая проверяется, если основная пуста, не поддерживается или ещё не привязана.
     * Один метод на обеих сторонах не даёт серверу и клиенту расходиться при двух предметах.
     *
     * @param player игрок, чьи текущие руки проверяются
     * @return привязанный NetworkId только у явно поддержанного предмета; иначе {@code null}
     */
    public static NetworkId activeNetwork(Player player) {
        NetworkId mainHand = readSupported(player.getMainHandItem());
        return mainHand != null ? mainHand : readSupported(player.getOffhandItem());
    }

    /** Проверяет контракт типа до чтения скопированной привязки предмета. */
    private static NetworkId readSupported(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof NetworkOutlineActivatingItem)) {
            return null;
        }
        return NetworkItemBinding.read(stack);
    }
}
