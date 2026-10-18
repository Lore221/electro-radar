package com.electroradar.radar.debug;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Блок без игрового интерфейса; серверную работу выполняет его BlockEntity. */
public final class DebugRadarBlock extends BaseEntityBlock {
    public static final MapCodec<DebugRadarBlock> CODEC = simpleCodec(DebugRadarBlock::new);

    public DebugRadarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new DebugRadarBlockEntity(position, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (level.isClientSide || type != DebugRadarContent.DEBUG_RADAR_ENTITY.get()) {
            return null;
        }
        // Тип проверен выше, поэтому приведение к BlockEntity временного радара безопасно.
        return (tickLevel, position, tickState, blockEntity) ->
                DebugRadarBlockEntity.serverTick(tickLevel, position, tickState,
                        (DebugRadarBlockEntity) blockEntity);
    }
}
