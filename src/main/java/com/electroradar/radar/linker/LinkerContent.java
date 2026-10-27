package com.electroradar.radar.linker;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

/** Регистрация единственного переносимого инструмента настройки радарной сети. */
public final class LinkerContent {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    public static final DeferredItem<LinkerItem> LINKER = ITEMS.register("linker",
            () -> new LinkerItem(new Item.Properties().stacksTo(1)));

    private LinkerContent() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
