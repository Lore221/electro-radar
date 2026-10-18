package com.electroradar.client.monitor;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.ElectroRadar;

/** Небольшие области atlas из старого монитора, общие для GUI и экранной поверхности в мире. */
public final class MonitorBlipSprite {
    public static final int ATLAS_SIZE = 256;
    /** Опорная ячейка старого рендера: иконка и рамка масштабируются относительно 8×8 пикселей. */
    public static final int CELL_SIZE = 8;
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ElectroRadar.MOD_ID, "textures/gui/radar_ui/icons.png");

    /** Текущая цель-сущность; координаты взяты из реально используемой области старого atlas. */
    public static final Region ENTITY = new Region(244, 252, 4, 4);
    /** Белая рамка для цели, на которую наведён курсор. */
    public static final Region HOVERED_FRAME = new Region(230, 248, 8, 8);
    /** Рамка выделения вокруг метки. */
    public static final Region SELECTED_FRAME = new Region(221, 248, 8, 8);
    /** Один непрозрачный пиксель atlas применяется как emissive-поверхность мировой сетки. */
    public static final Region WHITE_PIXEL = new Region(245, 253, 1, 1);

    private MonitorBlipSprite() {
    }

    /**
     * Рисует tintable-область atlas по центру точки и восстанавливает цвет шейдера.
     * {@code cellPixels} — размер исходной ячейки 8×8: сама иконка 4×4 занимает половину,
     * а рамка 8×8 — всю ячейку. Так метка и выделение меняются с масштабом карты одинаково.
     */
    public static void drawGui(GuiGraphics graphics, Region region, int centerX, int centerY,
                               int cellPixels, int argb) {
        int width = Math.max(1, Math.round(Math.max(1, cellPixels) * region.width / (float) CELL_SIZE));
        int height = Math.max(1, Math.round(Math.max(1, cellPixels) * region.height / (float) CELL_SIZE));
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        RenderSystem.setShaderColor(red / 255.0F, green / 255.0F,
                blue / 255.0F, alpha / 255.0F);
        try {
            graphics.blit(TEXTURE, centerX - width / 2, centerY - height / 2,
                    width, height, region.x, region.y, region.width, region.height,
                    ATLAS_SIZE, ATLAS_SIZE);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Точный прямоугольник прозрачного atlas для сетевой отрисовки. */
    public record Region(int x, int y, int width, int height) {
        public float minU() {
            return x / (float) ATLAS_SIZE;
        }

        public float maxU() {
            return (x + width) / (float) ATLAS_SIZE;
        }

        public float minV() {
            return y / (float) ATLAS_SIZE;
        }

        public float maxV() {
            return (y + height) / (float) ATLAS_SIZE;
        }
    }
}
