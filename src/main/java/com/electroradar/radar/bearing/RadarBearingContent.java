package com.electroradar.radar.bearing;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.network.NetworkBoundBlockItem;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RadarBearingContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ElectroRadar.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ElectroRadar.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ElectroRadar.MOD_ID);
    private static final DeferredRegister<ContraptionType> CONTRAPTIONS = DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, ElectroRadar.MOD_ID);
    public static final DeferredBlock<RadarBearingBlock> RADAR_BEARING = BLOCKS.registerBlock("radar_bearing", RadarBearingBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2).noOcclusion());
    public static final DeferredBlock<AntennaSectionBlock> ANTENNA_SECTION = BLOCKS.registerBlock("antenna_section", AntennaSectionBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F).noOcclusion());
    public static final DeferredItem<NetworkBoundBlockItem> RADAR_BEARING_ITEM = ITEMS.register("radar_bearing",
            () -> new NetworkBoundBlockItem(RADAR_BEARING.get(), new Item.Properties(), "message.electroradar.controller.bound", com.electroradar.radar.network.NetworkKind.SIMPLE_RADAR));
    public static final DeferredItem<BlockItem> ANTENNA_SECTION_ITEM = ITEMS.register("antenna_section", () -> new BlockItem(ANTENNA_SECTION.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBearingBlockEntity>> BEARING_ENTITY = ENTITIES.register("radar_bearing",
            () -> BlockEntityType.Builder.of(RadarBearingBlockEntity::new, RADAR_BEARING.get()).build(null));
    public static final DeferredHolder<ContraptionType, ContraptionType> ANTENNA_CONTRAPTION = CONTRAPTIONS.register("radar_antenna", () -> new ContraptionType(AntennaContraption::new));
    private RadarBearingContent() {}
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); CONTRAPTIONS.register(bus);
        BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                state.getBlock() instanceof RadarBearingBlock
                        && level.getBlockEntity(pos) instanceof RadarBearingBlockEntity bearing
                        && bearing.hasActiveAssembly() ? BlockMovementChecks.CheckResult.FAIL : BlockMovementChecks.CheckResult.PASS);
    }
}
