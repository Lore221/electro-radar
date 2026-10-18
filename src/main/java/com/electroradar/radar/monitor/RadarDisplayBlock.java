package com.electroradar.radar.monitor;

import com.mojang.serialization.MapCodec;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkItemBinding;

/**
 * Тонкая панель монитора. Физическая сборка ограничена одним направлением и прямоугольником
 * до 5×5; клиентские свойства ACTIVE и FRAME_SHAPE обновляются серверным резолвером конструкции.
 */
public final class RadarDisplayBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<RadarDisplayBlock> CODEC = simpleCodec(RadarDisplayBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final EnumProperty<RadarDisplayFrameShape> FRAME_SHAPE =
            EnumProperty.create("frame_shape", RadarDisplayFrameShape.class);
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new RadarDisplayPlacementHelper());

    private static final VoxelShape NORTH_SHAPE = Block.box(0, 0, 2, 16, 16, 14);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0, 0, 2, 16, 16, 14);
    private static final VoxelShape EAST_SHAPE = Block.box(2, 0, 0, 14, 16, 16);
    private static final VoxelShape WEST_SHAPE = Block.box(2, 0, 0, 14, 16, 16);

    public RadarDisplayBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false)
                .setValue(FRAME_SHAPE, RadarDisplayFrameShape.SINGLE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        Direction facing = context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                ? context.getHorizontalDirection()
                : clickedFace.getAxis().isHorizontal() ? clickedFace : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            RadarDisplayBlockEntity.panelPlaced(level, pos, level.getBlockEntity(pos));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            RadarDisplayBlockEntity.panelRemoved(level, pos, level.getBlockEntity(pos));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Create placement helper is the only interaction which joins two separate panel identities. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        IPlacementHelper helper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
        if (!player.isShiftKeyDown() && player.mayBuild() && helper.matchesState(state)
                && helper.matchesItem(stack) && stack.getItem() instanceof BlockItem blockItem) {
            RadarDisplayPlacementHelper displayHelper = (RadarDisplayPlacementHelper) helper;
            BlockPos targetPos = displayHelper.findTargetPos(level, state, pos, hitResult).orElse(null);
            if (targetPos == null || !(level.getBlockEntity(pos) instanceof RadarDisplayBlockEntity member)) {
                return ItemInteractionResult.FAIL;
            }
            NetworkId itemNetworkId = NetworkItemBinding.read(stack);
            if (!member.canJoinNetwork(itemNetworkId)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.electroradar.monitor.network_mismatch"), true);
                }
                return ItemInteractionResult.FAIL;
            }
            PlacementOffset offset = helper.getOffset(player, level, state, pos, hitResult);
            ItemInteractionResult placementResult = offset.placeInWorld(
                    level, blockItem, player, hand, hitResult);
            if (placementResult.consumesAction()
                    && level.getBlockEntity(targetPos) instanceof RadarDisplayBlockEntity placed) {
                placed.joinDisplay(member, itemNetworkId);
            }
            return placementResult;
        }

        if (hitResult.getDirection() != state.getValue(FACING)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof RadarDisplayBlockEntity member) {
            RadarDisplayBlockEntity root = state.getValue(ACTIVE) ? member.loadedRoot() : null;
            if (root != null) {
                root.openScreen(serverPlayer);
            } else {
                player.displayClientMessage(Component.translatable("message.electroradar.monitor.inactive"), true);
            }
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (hitResult.getDirection() != state.getValue(FACING)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof RadarDisplayBlockEntity member) {
            RadarDisplayBlockEntity root = state.getValue(ACTIVE) ? member.loadedRoot() : null;
            if (root != null) {
                root.openScreen(serverPlayer);
            } else {
                player.displayClientMessage(Component.translatable("message.electroradar.monitor.inactive"), true);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE, FRAME_SHAPE);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState.setValue(FACING, originalState.getValue(FACING).getClockWise());
    }

    /**
     * Create меняет FACING через IWrenchable и не вызывает onPlace для того же блока.
     * Поэтому после штатного поворота отдельно помечаем серверную сборку для пересчёта.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (result.consumesAction()) {
            RadarDisplayBlockEntity.panelRotated(context.getLevel(), context.getClickedPos());
        }
        return result;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadarDisplayBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != RadarDisplayContent.RADAR_DISPLAY_ENTITY.get()) return null;
        return (tickLevel, position, tickState, blockEntity) ->
                RadarDisplayBlockEntity.serverTick(tickLevel, position, tickState,
                        (RadarDisplayBlockEntity) blockEntity);
    }
}
