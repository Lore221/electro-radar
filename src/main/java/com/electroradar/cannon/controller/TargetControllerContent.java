package com.electroradar.cannon.controller;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.electroradar.ElectroRadar;

/** Регистрация контроллера наведения; в творческую вкладку его добавляет общий список радаров. */
public final class TargetControllerContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredBlock<TargetControllerBlock> TARGET_CONTROLLER = BLOCKS.registerBlock(
            "target_controller", TargetControllerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).requiresCorrectToolForDrops());
    public static final DeferredItem<TargetControllerItem> TARGET_CONTROLLER_ITEM = ITEMS.register(
            "target_controller", () -> TargetControllerItem.create(TARGET_CONTROLLER.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TargetControllerBlockEntity>>
            TARGET_CONTROLLER_ENTITY = BLOCK_ENTITIES.register("target_controller", () -> BlockEntityType.Builder
                    .of(TargetControllerBlockEntity::new, TARGET_CONTROLLER.get()).build(null));

    private TargetControllerContent() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
