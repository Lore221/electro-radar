package com.electroradar.radar.alarm;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.Containers;
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

/** Стандартный сетевой потребитель, без питания и без собственных поисков сущностей. */
public final class ShellAlarmBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<ShellAlarmBlock> CODEC = simpleCodec(ShellAlarmBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public ShellAlarmBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public BlockState getRotatedBlockState(BlockState state, Direction face) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ShellAlarmBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != ShellAlarmContent.SHELL_ALARM_ENTITY.get() ? null
                : (world, pos, blockState, entity) -> ((ShellAlarmBlockEntity) entity).serverTick();
    }
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!player.mayBuild() || !world.mayInteract(player, pos)) return InteractionResult.FAIL;
        if (!world.isClientSide && world.getBlockEntity(pos) instanceof ShellAlarmBlockEntity alarm) {
            var stack = alarm.extractSelector();
            if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && !world.isClientSide
                && world.getBlockEntity(pos) instanceof ShellAlarmBlockEntity alarm) {
            var stack = alarm.extractSelector();
            if (!stack.isEmpty()) Containers.dropItemStack(world, pos.getX(), pos.getY(), pos.getZ(), stack);
        }
        super.onRemove(state, world, pos, replacement, moving);
    }

    @Override protected boolean isSignalSource(BlockState state) { return true; }
    @Override protected int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction side) {
        return world.getBlockEntity(pos) instanceof ShellAlarmBlockEntity alarm && !alarm.dangerousProjectiles().isEmpty() ? 15 : 0;
    }
    @Override protected int getDirectSignal(BlockState state, BlockGetter world, BlockPos pos, Direction side) {
        return getSignal(state, world, pos, side);
    }
}
