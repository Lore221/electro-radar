package com.electroradar.radar.debug;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

/**
 * Единая точка deferred-регистрации блока, выдаваемого предмета и BlockEntity.
 * Собственный BlockItem регистрируется под тем же именем, поэтому блок можно получить командой
 * {@code /give @s electroradar:debug_radar} без художественных ресурсов.
 */
public final class DebugRadarContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredBlock<DebugRadarBlock> DEBUG_RADAR_BLOCK = BLOCKS.registerBlock(
            "debug_radar", DebugRadarBlock::new, BlockBehaviour.Properties.of().strength(1.5F));
    public static final DeferredItem<DebugRadarItem> DEBUG_RADAR_ITEM = ITEMS.register("debug_radar",
            () -> new DebugRadarItem(DEBUG_RADAR_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DebugRadarBlockEntity>> DEBUG_RADAR_ENTITY =
            BLOCK_ENTITIES.register("debug_radar", () -> BlockEntityType.Builder
                    .of(DebugRadarBlockEntity::new, DEBUG_RADAR_BLOCK.get())
                    .build(null));

    private DebugRadarContent() {
    }

    /** Подключает реестры к mod event bus до обработки соответствующих RegisterEvent. */
    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
    }
}
