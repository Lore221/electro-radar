package com.electroradar.radar.controller;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import com.simibubi.create.content.kinetics.base.IRotate;

/** Общий блок для трёх стационарных контроллеров; режим задаётся только неизменяемым полем. */
public final class RadarControllerBlock extends BaseEntityBlock implements IRotate {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private final RadarMode mode;

    public RadarControllerBlock(RadarMode mode, BlockBehaviour.Properties properties) {
        super(properties);
        this.mode = mode;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public RadarMode mode() {
        return mode;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        // Codec сохраняет выбранный тип блока при декодировании его свойств.
        return simpleCodec(properties -> new RadarControllerBlock(mode, properties));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                ? context.getHorizontalDirection()
                : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()
                || hit.getDirection() != state.getValue(FACING)) {
            return InteractionResult.PASS;
        }
        if (level.getBlockEntity(pos) instanceof RadarControllerBlockEntity controller) {
            if (!level.isClientSide) controller.toggleAssembly(player);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState replacement, boolean movedByPiston) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof RadarControllerBlockEntity controller) {
            controller.prepareForBlockRemoval();
        }
        super.onRemove(state, level, pos, replacement, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadarControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != RadarControllerContent.CONTROLLER_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) ->
                RadarControllerBlockEntity.serverTick(tickLevel, pos, tickState,
                        (RadarControllerBlockEntity) blockEntity);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Нельзя повернуть блок раньше успешной разборки его старой апертуры. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(context.getClickedPos()) instanceof RadarControllerBlockEntity controller
                && !controller.prepareForControllerRotation()) {
            Player player = context.getPlayer();
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "message.electroradar.assembly.rotation_deferred"), true);
            }
            return InteractionResult.CONSUME;
        }
        return IRotate.super.onWrenched(state, context);
    }

    /** Create wrench вращает только горизонтальную ориентацию; кинетический интерфейс не добавляется. */
    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }
}
