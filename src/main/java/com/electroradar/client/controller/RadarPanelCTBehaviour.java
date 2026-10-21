package com.electroradar.client.controller;

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

import org.jetbrains.annotations.Nullable;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.controller.RadarControllerContent;
import com.electroradar.radar.controller.RadarPanelBlock;

/**
 * Только клиентское CT поведение. Соединение вычисляется из загруженных соседних blockstate,
 * профиля и ориентации; владелец сети и серверный радар при прорисовке квадрата не запрашиваются.
 */
public final class RadarPanelCTBehaviour extends ConnectedTextureBehaviour.Base {
    private static final CTSpriteShiftEntry FRONT = omnidirectional("radar_panel/radar_panel_front");
    private static final CTSpriteShiftEntry BACK_SURFACE = omnidirectional(
            "radar_panel/radar_panel_back", "radar_panel/radar_panel_back_connected");
    private static final CTSpriteShiftEntry BACK_BASED = omnidirectional(
            "radar_panel/radar_panel_back", "radar_panel/radar_panel_back_connected_based");
    private static final CTSpriteShiftEntry BACK_AIR = omnidirectional(
            "radar_panel/radar_panel_back", "radar_panel/radar_panel_back_air");
    private static final CTSpriteShiftEntry SIDE_SURFACE = rectangle(
            "radar_panel/radar_panel_side", "radar_panel/radar_panel_side_connected");
    private static final CTSpriteShiftEntry SIDE_BASED = rectangle(
            "radar_panel/radar_panel_side", "radar_panel/radar_panel_side_connected_based");
    private static final CTSpriteShiftEntry SIDE_AIR = rectangle(
            "radar_panel/radar_panel_side", "radar_panel/radar_panel_side_connected_air");

    private static CTSpriteShiftEntry side(RadarPanelBlock.PanelProfile profile) {
        return switch (profile) {
            case BASED -> SIDE_BASED;
            case AIR -> SIDE_AIR;
            case SURFACE -> SIDE_SURFACE;
        };
    }

    private static CTSpriteShiftEntry back(RadarPanelBlock.PanelProfile profile) {
        return switch (profile) {
            case BASED -> BACK_BASED;
            case AIR -> BACK_AIR;
            case SURFACE -> BACK_SURFACE;
        };
    }

    private static CTSpriteShiftEntry rectangle(String original, String connected) {
        return CTSpriteShifter.getCT(AllCTTypes.RECTANGLE, id(original), id(connected));
    }

    private static CTSpriteShiftEntry omnidirectional(String original) {
        return omnidirectional(original, original + "_connected");
    }

    private static CTSpriteShiftEntry omnidirectional(String original, String connected) {
        return CTSpriteShifter.getCT(AllCTTypes.OMNIDIRECTIONAL, id(original), id(connected));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "block/" + path);
    }

    @Override
    @Nullable
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction,
                                      @Nullable TextureAtlasSprite sprite) {
        if (!state.is(RadarControllerContent.RADAR_PANEL.get())
                || !state.hasProperty(RadarPanelBlock.FACING)) return null;
        Direction facing = state.getValue(RadarPanelBlock.FACING);
        RadarPanelBlock.PanelProfile profile = state.getValue(RadarPanelBlock.PROFILE);
        CTSpriteShiftEntry shift = direction == facing ? FRONT
                : direction == facing.getOpposite() ? back(profile) : side(profile);
        return sprite == null || shift.getOriginal() == sprite ? shift : null;
    }

    @Override
    protected Direction getUpDirection(BlockAndTintGetter level, BlockPos pos,
                                       BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(RadarPanelBlock.FACING)) {
            return state.getValue(RadarPanelBlock.FACING).getCounterClockWise();
        }
        return super.getUpDirection(level, pos, state, face);
    }

    @Override
    protected Direction getRightDirection(BlockAndTintGetter level, BlockPos pos,
                                          BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(RadarPanelBlock.FACING)) {
            // На лицевой плоскости горизонтальный CT-ряд смотрит в глубину панели.
            return state.getValue(RadarPanelBlock.FACING);
        }
        return super.getRightDirection(level, pos, state, face);
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter level,
                              BlockPos pos, BlockPos otherPos, Direction face) {
        if (!super.connectsTo(state, other, level, pos, otherPos, face)
                || !RadarPanelBlock.isPanel(state) || !RadarPanelBlock.isPanel(other)
                || state.getValue(RadarPanelBlock.FACING) != other.getValue(RadarPanelBlock.FACING)
                || state.getValue(RadarPanelBlock.PROFILE) != other.getValue(RadarPanelBlock.PROFILE)) {
            return false;
        }
        Direction facing = state.getValue(RadarPanelBlock.FACING);
        if (face == facing || face == facing.getOpposite()) return true;
        Direction connection = connectionDirection(pos, otherPos);
        Direction panelRight = facing.getCounterClockWise();
        boolean followsAperture = connection == Direction.UP || connection == Direction.DOWN
                || connection == panelRight || connection == panelRight.getOpposite();
        return followsAperture && isExposedSide(level, pos, state, face)
                && isExposedSide(level, otherPos, other, face);
    }

    private static boolean isExposedSide(BlockAndTintGetter level, BlockPos pos,
                                         BlockState panel, Direction side) {
        BlockState outward = level.getBlockState(pos.relative(side));
        return !RadarPanelBlock.isPanel(outward)
                || outward.getValue(RadarPanelBlock.FACING) != panel.getValue(RadarPanelBlock.FACING)
                || outward.getValue(RadarPanelBlock.PROFILE) != panel.getValue(RadarPanelBlock.PROFILE);
    }

    @Nullable
    private static Direction connectionDirection(BlockPos pos, BlockPos otherPos) {
        for (Direction direction : Direction.values()) {
            if (pos.relative(direction).equals(otherPos)) return direction;
        }
        return null;
    }
}
