package com.electroradar.radar.controller;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.registry.CreateRegistries;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.RadarDisplayContent;

/** Регистрация контроллеров, панели, единого BlockEntity и творческой вкладки. */
public final class RadarControllerContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ElectroRadar.MOD_ID);
    private static final DeferredRegister<ContraptionType> CONTRAPTION_TYPES =
            DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, ElectroRadar.MOD_ID);

    public static final DeferredHolder<ContraptionType, ContraptionType> PANEL_CONTRAPTION_TYPE =
            CONTRAPTION_TYPES.register("radar_panel", () -> new ContraptionType(RadarPanelContraption::new));

    public static final DeferredBlock<RadarControllerBlock> RADAR_CONTROLLER = controller("radar_controller", RadarMode.GROUND);
    public static final DeferredBlock<RadarControllerBlock> AIR_RADAR_CONTROLLER = controller("air_radar_controller", RadarMode.AIR);
    public static final DeferredBlock<RadarControllerBlock> SURFACE_RADAR_CONTROLLER = controller("surface_radar_controller", RadarMode.SURFACE);
    public static final DeferredBlock<RadarPanelBlock> RADAR_PANEL = BLOCKS.registerBlock(
            "radar_panel", RadarPanelBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).noOcclusion());

    public static final DeferredItem<RadarControllerItem> RADAR_CONTROLLER_ITEM = controllerItem("radar_controller", RADAR_CONTROLLER);
    public static final DeferredItem<RadarControllerItem> AIR_RADAR_CONTROLLER_ITEM = controllerItem("air_radar_controller", AIR_RADAR_CONTROLLER);
    public static final DeferredItem<RadarControllerItem> SURFACE_RADAR_CONTROLLER_ITEM = controllerItem("surface_radar_controller", SURFACE_RADAR_CONTROLLER);
    public static final DeferredItem<BlockItem> RADAR_PANEL_ITEM = ITEMS.register("radar_panel",
            () -> new BlockItem(RADAR_PANEL.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarControllerBlockEntity>> CONTROLLER_ENTITY =
            BLOCK_ENTITIES.register("radar_controller", () -> BlockEntityType.Builder.of(
                    RadarControllerBlockEntity::new,
                    RADAR_CONTROLLER.get(), AIR_RADAR_CONTROLLER.get(), SURFACE_RADAR_CONTROLLER.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = TABS.register(
            "radars", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.electroradar.radars"))
                    .icon(() -> new ItemStack(RADAR_CONTROLLER_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(RADAR_CONTROLLER_ITEM.get());
                        output.accept(AIR_RADAR_CONTROLLER_ITEM.get());
                        output.accept(SURFACE_RADAR_CONTROLLER_ITEM.get());
                        output.accept(RADAR_PANEL_ITEM.get());
                        output.accept(RadarDisplayContent.RADAR_DISPLAY_ITEM.get());
                    }).build());

    private RadarControllerContent() {
    }

    private static DeferredBlock<RadarControllerBlock> controller(String id, RadarMode mode) {
        return BLOCKS.registerBlock(id,
                properties -> new RadarControllerBlock(mode, properties),
                BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F));
    }

    private static DeferredItem<RadarControllerItem> controllerItem(
            String id, DeferredBlock<RadarControllerBlock> block) {
        return ITEMS.register(id, () -> new RadarControllerItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
        CONTRAPTION_TYPES.register(bus);
    }

    /**
     * Create проверяет MovementAllowedCheck до capture блока и до removeBlocksFromWorld. Пока
     * контроллер владеет панелями, FAIL не даёт внешней contraption забрать его BlockEntity и
     * оставить ControlledContraptionEntity без опоры. Callback только читает состояние мира;
     * регистрации Create thread-safe и ставится один раз при создании мода.
     */
    public static void registerCreateMovementPolicy() {
        BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) -> {
            if (state.getBlock() instanceof RadarControllerBlock
                    && level.getBlockEntity(pos) instanceof RadarControllerBlockEntity controller
                    && controller.hasActiveAssembly()) {
                return BlockMovementChecks.CheckResult.FAIL;
            }
            return BlockMovementChecks.CheckResult.PASS;
        });
    }
}
