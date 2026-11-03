package com.electroradar.cannon.controller;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

/**
 * Контроллер наведения ставится вплотную к установке CBC лицевой гранью к ней.
 *
 * <p>Сигнал выстрела подаётся только в сторону установки: установка CBC с другой стороны принимает
 * сигнал сборки, и импульс туда разобрал бы орудие. Стрелять контроллер начинает, только если
 * смотрит на вход выстрела установки.</p>
 */
public final class TargetControllerBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<TargetControllerBlock> CODEC = simpleCodec(TargetControllerBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public TargetControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** Поставленный на боковую грань контроллер смотрит на этот блок, иначе — от игрока. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal()
                ? clicked.getOpposite() : context.getHorizontalDirection();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** {@code direction} указывает от соседа на контроллер: сигнал получает только установка спереди. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return direction == state.getValue(FACING).getOpposite()
                && level.getBlockEntity(pos) instanceof TargetControllerBlockEntity controller
                && controller.firing() ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TargetControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != TargetControllerContent.TARGET_CONTROLLER_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) ->
                ((TargetControllerBlockEntity) blockEntity).serverTick();
    }
}
