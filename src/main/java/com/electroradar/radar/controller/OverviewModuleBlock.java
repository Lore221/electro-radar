package com.electroradar.radar.controller;

import com.mojang.serialization.MapCodec;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ItemInteractionResult;

/** Обычная деталь вертикальной апертуры без собственного блока-сущности и сетевого ID. */
public final class OverviewModuleBlock extends Block {
    public static final MapCodec<OverviewModuleBlock> CODEC = simpleCodec(OverviewModuleBlock::new);
    public static final BooleanProperty CONNECTED_UP = BooleanProperty.create("connected_up");
    public static final BooleanProperty CONNECTED_DOWN = BooleanProperty.create("connected_down");
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new OverviewModulePlacementHelper());
    private static final VoxelShape FULL_BLOCK_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    public OverviewModuleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CONNECTED_UP, false)
                .setValue(CONNECTED_DOWN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        reconcileAround(level, pos);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos,
                            BlockState newState, boolean moving) {
        super.onRemove(oldState, level, pos, newState, moving);
        if (!oldState.is(newState.getBlock())) reconcileAround(level, pos);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP) {
            return state.setValue(CONNECTED_UP, isOverviewModule(neighborState));
        }
        if (direction == Direction.DOWN) {
            return state.setValue(CONNECTED_DOWN, isOverviewModule(neighborState));
        }
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return FULL_BLOCK_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return FULL_BLOCK_SHAPE;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hitResult) {
        IPlacementHelper helper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
        if (player.isShiftKeyDown() || !player.mayBuild() || !helper.matchesState(state)
                || !helper.matchesItem(stack) || !(stack.getItem() instanceof BlockItem blockItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        PlacementOffset offset = helper.getOffset(player, level, state, pos, hitResult);
        if (!offset.isSuccessful()) return ItemInteractionResult.FAIL;
        ItemInteractionResult result = offset.placeInWorld(level, blockItem, player, hand, hitResult);
        return result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                ? ItemInteractionResult.FAIL : result;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED_UP, CONNECTED_DOWN);
    }

    public static boolean isOverviewModule(BlockState state) {
        return state.is(RadarControllerContent.OVERVIEW_MODULE.get());
    }

    /** Пересчитывает только три загруженные позиции вокруг изменения, не запрашивая соседние чанки. */
    public static void reconcileAround(LevelAccessor level, BlockPos changedPos) {
        if (level.isClientSide()) return;
        updateConnectionState(level, changedPos.below());
        updateConnectionState(level, changedPos);
        updateConnectionState(level, changedPos.above());
    }

    private static void updateConnectionState(LevelAccessor level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!isOverviewModule(state)) return;
        BlockPos above = pos.above();
        BlockPos below = pos.below();
        boolean connectedUp = level.hasChunkAt(above) && isOverviewModule(level.getBlockState(above));
        boolean connectedDown = level.hasChunkAt(below) && isOverviewModule(level.getBlockState(below));
        BlockState updated = state.setValue(CONNECTED_UP, connectedUp).setValue(CONNECTED_DOWN, connectedDown);
        if (!updated.equals(state)) level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
    }
}
