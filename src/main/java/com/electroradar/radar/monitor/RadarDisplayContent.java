package com.electroradar.radar.monitor;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

/** Deferred-регистрация составного блока, его предмета и BlockEntity. */
public final class RadarDisplayContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredBlock<RadarDisplayBlock> RADAR_DISPLAY_BLOCK = BLOCKS.registerBlock(
            "radar_display", RadarDisplayBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).noOcclusion());
    public static final DeferredItem<RadarDisplayItem> RADAR_DISPLAY_ITEM = ITEMS.register("radar_display",
            () -> new RadarDisplayItem(RADAR_DISPLAY_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarDisplayBlockEntity>> RADAR_DISPLAY_ENTITY =
            BLOCK_ENTITIES.register("radar_display", () -> BlockEntityType.Builder
                    .of(RadarDisplayBlockEntity::new, RADAR_DISPLAY_BLOCK.get()).build(null));

    private RadarDisplayContent() {
    }

    /** Подключает реестры к mod event bus. */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
    }
}
