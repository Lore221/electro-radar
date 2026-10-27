package com.electroradar.radar.controller;

import com.mojang.serialization.MapCodec;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Визуальная панель апертуры. Её смысл в сети появляется только при захвате контроллером. */
public final class RadarPanelBlock extends HorizontalDirectionalBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<PanelProfile> PROFILE = EnumProperty.create("profile", PanelProfile.class);
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new RadarPanelPlacementHelper());
    private static final VoxelShape NORTH = Block.box(0, 0, 0, 16, 16, 13);
    private static final VoxelShape SOUTH = Block.box(0, 0, 3, 16, 16, 16);
    private static final VoxelShape EAST = Block.box(3, 0, 0, 16, 16, 16);
    private static final VoxelShape WEST = Block.box(0, 0, 0, 13, 16, 16);

    public RadarPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH).setValue(PROFILE, PanelProfile.SURFACE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(RadarPanelBlock::new);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (context.getLevel().hasChunkAt(pos.below())
                && context.getLevel().getBlockState(pos.below()).getBlock() instanceof RadarControllerBlock controller) {
            return defaultBlockState().setValue(FACING,
                    context.getLevel().getBlockState(pos.below()).getValue(RadarControllerBlock.FACING));
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (context.getLevel().hasChunkAt(neighbor)
                    && isPanel(context.getLevel().getBlockState(neighbor))) {
                return defaultBlockState().setValue(FACING,
                        context.getLevel().getBlockState(neighbor).getValue(FACING));
            }
        }
        Direction facing = context.getClickedFace().getAxis().isHorizontal()
                ? context.getClickedFace() : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    /** Shift оставляет обычное размещение; помощник расширяет панель вверх/вниз или вбок. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        IPlacementHelper helper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
        if (player.isShiftKeyDown() || !player.mayBuild() || !helper.matchesItem(stack)
                || !helper.matchesState(state) || !(stack.getItem() instanceof BlockItem blockItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        var offset = helper.getOffset(player, level, state, pos, hitResult);
        // Отказ не должен превращаться в обычную установку перед апертурой или за ней.
        if (!offset.isSuccessful()) return ItemInteractionResult.FAIL;
        ItemInteractionResult result = offset.placeInWorld(level, blockItem, player, hand, hitResult);
        return result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                ? ItemInteractionResult.FAIL : result;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PROFILE);
    }

    public static boolean isPanel(BlockState state) {
        return state.is(RadarControllerContent.RADAR_PANEL.get()) && state.hasProperty(FACING);
    }

    public enum PanelProfile implements StringRepresentable {
        SURFACE("surface"), BASED("based"), AIR("air");

        private final String name;

        PanelProfile(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public static PanelProfile forMode(RadarMode mode) {
            return switch (mode) {
                case GROUND -> BASED;
                case AIR -> AIR;
                case SURFACE -> SURFACE;
            };
        }
    }
}
