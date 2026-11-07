package com.electroradar.cannon.controller;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

/** Регистрация контроллера перехвата и его взрывателя; в творческую вкладку его добавляет общий список радаров. */
public final class InterceptControllerContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredBlock<InterceptControllerBlock> INTERCEPT_CONTROLLER = BLOCKS.registerBlock(
            "intercept_controller", InterceptControllerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).requiresCorrectToolForDrops());
    public static final DeferredItem<InterceptControllerItem> INTERCEPT_CONTROLLER_ITEM = ITEMS.register(
            "intercept_controller", () -> InterceptControllerItem.create(INTERCEPT_CONTROLLER.get()));
    public static final DeferredItem<InterceptFuzeItem> INTERCEPT_FUZE = ITEMS.registerItem(
            "intercept_fuze", InterceptFuzeItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InterceptControllerBlockEntity>>
            INTERCEPT_CONTROLLER_ENTITY = BLOCK_ENTITIES.register("intercept_controller", () -> BlockEntityType.Builder
                    .of(InterceptControllerBlockEntity::new, INTERCEPT_CONTROLLER.get()).build(null));

    private InterceptControllerContent() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        NeoForge.EVENT_BUS.addListener(ProgrammedFuzes::onJoin);
        NeoForge.EVENT_BUS.addListener(ProgrammedFuzes::onLeave);
        NeoForge.EVENT_BUS.addListener(ProgrammedFuzes::onServerStopped);
    }
}
