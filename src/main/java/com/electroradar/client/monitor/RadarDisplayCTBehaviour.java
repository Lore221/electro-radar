package com.electroradar.client.monitor;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.monitor.RadarDisplayBlock;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.RadarDisplayFrameShape;
import com.electroradar.radar.monitor.RadarDisplayStructureResolver;

/**
 * Create Connected Textures для рамки монитора.
 *
 * <p>Шов исчезает только между активными панелями одной физической сборки. NetworkId намеренно
 * не участвует: два самостоятельных экрана одной радарной сети сохраняют собственные рамки.</p>
 */
public final class RadarDisplayCTBehaviour extends ConnectedTextureBehaviour.Base {
    private static final CTSpriteShiftEntry FRONT = shift("radar_display/radar_display_front",
            "radar_display/radar_display_front_connected");
    private static final CTSpriteShiftEntry BACK = shift("radar_display/radar_display_back",
            "radar_display/radar_display_back_connectted");
    private static final CTSpriteShiftEntry SIDE_HORIZONTAL = shift("radar_display/radar_display_side_horizontal",
            "radar_display/radar_display_side_horizontal_connected");
    private static final CTSpriteShiftEntry SIDE_VERTICAL = shift("radar_display/radar_display_side_vertical",
            "radar_display/radar_display_side_vertical_connected");

    @Override
    @Nullable
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
        Direction facing = state.getValue(RadarDisplayBlock.FACING);
        CTSpriteShiftEntry selected = direction == facing ? FRONT
                : direction == facing.getOpposite() ? BACK
                : direction.getAxis().isVertical() ? SIDE_HORIZONTAL : SIDE_VERTICAL;
        return sprite == null || selected.getOriginal() == sprite ? selected : null;
    }

    @Override
    protected Direction getRightDirection(BlockAndTintGetter level, BlockPos pos,
                                          BlockState state, Direction face) {
        if (face.getAxis().isVertical()) {
            Direction right = RadarDisplayStructureResolver.right(state.getValue(RadarDisplayBlock.FACING));
            return face == Direction.UP ? right.getOpposite() : right;
        }
        return super.getRightDirection(level, pos, state, face);
    }

    @Override
    protected Direction getUpDirection(BlockAndTintGetter level, BlockPos pos,
                                       BlockState state, Direction face) {
        if (face.getAxis().isVertical()) return state.getValue(RadarDisplayBlock.FACING);
        return super.getUpDirection(level, pos, state, face);
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter level,
                              BlockPos pos, BlockPos otherPos, Direction face) {
        if (!super.connectsTo(state, other, level, pos, otherPos, face)
                || state.getValue(RadarDisplayBlock.FACING) != other.getValue(RadarDisplayBlock.FACING)
                || !state.getValue(RadarDisplayBlock.ACTIVE)
                || !other.getValue(RadarDisplayBlock.ACTIVE)
                || !(level.getBlockEntity(pos) instanceof RadarDisplayBlockEntity panel)
                || !(level.getBlockEntity(otherPos) instanceof RadarDisplayBlockEntity otherPanel)
                || panel.displayId() == null
                || !panel.displayId().equals(otherPanel.displayId())) {
            return false;
        }
        Direction connection = connectionDirection(pos, otherPos);
        if (connection == null) return false;
        Direction facing = state.getValue(RadarDisplayBlock.FACING);
        RadarDisplayFrameShape first = state.getValue(RadarDisplayBlock.FRAME_SHAPE);
        RadarDisplayFrameShape second = other.getValue(RadarDisplayBlock.FRAME_SHAPE);
        RadarDisplayFrameShape edge = edge(facing, connection);
        RadarDisplayFrameShape opposite = edge(facing, connection.getOpposite());
        return edge != null && opposite != null && !hasEdge(first, edge) && !hasEdge(second, opposite);
    }

    private static RadarDisplayFrameShape edge(Direction facing, Direction direction) {
        if (direction == Direction.UP) return RadarDisplayFrameShape.TOP;
        if (direction == Direction.DOWN) return RadarDisplayFrameShape.BOTTOM;
        Direction right = RadarDisplayStructureResolver.right(facing);
        if (direction == right) return RadarDisplayFrameShape.RIGHT;
        if (direction == right.getOpposite()) return RadarDisplayFrameShape.LEFT;
        return null;
    }

    private static boolean hasEdge(RadarDisplayFrameShape shape, RadarDisplayFrameShape edge) {
        return switch (edge) {
            case TOP -> shape.hasTopEdge();
            case BOTTOM -> shape.hasBottomEdge();
            case LEFT -> shape.hasLeftEdge();
            case RIGHT -> shape.hasRightEdge();
            default -> true;
        };
    }

    @Nullable
    private static Direction connectionDirection(BlockPos first, BlockPos second) {
        for (Direction direction : Direction.values()) {
            if (first.relative(direction).equals(second)) return direction;
        }
        return null;
    }

    private static CTSpriteShiftEntry shift(String original, String connected) {
        ResourceLocation base = ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "block/" + original);
        ResourceLocation connectedTexture = ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID,
                "block/" + connected);
        return CTSpriteShifter.getCT(AllCTTypes.RECTANGLE, base, connectedTexture);
    }
}
