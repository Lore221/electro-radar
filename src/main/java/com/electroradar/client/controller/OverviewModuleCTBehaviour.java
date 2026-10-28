package com.electroradar.client.controller;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import com.electroradar.ElectroRadar;

/** Вертикальные CT-атласы исходной OBJ-модели обзорного модуля. */
public final class OverviewModuleCTBehaviour extends ConnectedTextureBehaviour.Base {
    private static final CTSpriteShiftEntry FRONT = vertical("front", 31);
    private static final CTSpriteShiftEntry BACK = vertical("back", 31);
    private static final CTSpriteShiftEntry SIDE = vertical("side", 5);

    @Override
    @Nullable
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction,
                                       @Nullable TextureAtlasSprite sprite) {
        if (sprite != null) {
            if (FRONT.getOriginal() == sprite) return FRONT;
            if (BACK.getOriginal() == sprite) return BACK;
            if (SIDE.getOriginal() == sprite) return SIDE;
            return null;
        }
        // Все рабочие поверхности OBJ вертикальны; контексту достаточно типа VERTICAL.
        return direction.getAxis().isVertical() ? null : FRONT;
    }

    private static CTSpriteShiftEntry vertical(String texture, int contentWidth) {
        ResourceLocation original = id("block/overview_module/overview_module_" + texture);
        ResourceLocation connected = id("block/overview_module/overview_module_" + texture + "_connected");
        return new VerticalSpriteShift(original, connected, contentWidth);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, path);
    }

    private static final class VerticalSpriteShift extends CTSpriteShiftEntry {
        private static final float ATLAS_SIZE = 64.0F;
        private static final float ROW_HEIGHT = 16.0F / ATLAS_SIZE;
        private final float contentWidth;

        private VerticalSpriteShift(ResourceLocation original, ResourceLocation connected, int contentWidth) {
            super(AllCTTypes.VERTICAL);
            this.contentWidth = contentWidth / ATLAS_SIZE;
            set(original, connected);
        }

        @Override
        public float getTargetU(float localU, int index) {
            float sourceU = getUnInterpolatedU(getOriginal(), localU);
            return getTarget().getU(sourceU * contentWidth);
        }

        @Override
        public float getTargetV(float localV, int index) {
            float sourceV = getUnInterpolatedV(getOriginal(), localV);
            return getTarget().getV((sourceV + atlasRow(index)) * ROW_HEIGHT);
        }

        private static int atlasRow(int index) {
            return switch (index) {
                case 1 -> 3;
                case 2 -> 1;
                case 3 -> 2;
                default -> 0;
            };
        }
    }
}
