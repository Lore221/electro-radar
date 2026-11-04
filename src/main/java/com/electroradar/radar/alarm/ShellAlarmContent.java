package com.electroradar.radar.alarm;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

public final class ShellAlarmContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredBlock<ShellAlarmBlock> SHELL_ALARM = BLOCKS.registerBlock("shell_alarm", ShellAlarmBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).requiresCorrectToolForDrops().noOcclusion());
    public static final DeferredItem<ShellAlarmItem> SHELL_ALARM_ITEM = ITEMS.register("shell_alarm",
            () -> new ShellAlarmItem(SHELL_ALARM.get()));
    public static final DeferredItem<ZoneSelectionItem> ZONE_SELECTOR = ITEMS.register("zone_selector",
            () -> new ZoneSelectionItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShellAlarmBlockEntity>> SHELL_ALARM_ENTITY =
            ENTITIES.register("shell_alarm", () -> BlockEntityType.Builder.of(ShellAlarmBlockEntity::new, SHELL_ALARM.get()).build(null));

    private ShellAlarmContent() { }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
    }
}
