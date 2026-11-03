package com.electroradar.client.cannon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import com.simibubi.create.foundation.gui.AllIcons;

import com.electroradar.ElectroRadar;
import com.electroradar.cannon.controller.TrajectoryMode;

/**
 * Иконка режима траектории для панели значений Create. Create рисует её и в интерфейсе, и над
 * блоком, поэтому переопределены оба способа отрисовки; атлас Create не используется.
 */
final class TrajectoryModeIcon extends AllIcons {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ElectroRadar.MOD_ID, "textures/gui/target_controller/trajectory_modes.png");
    private static final int SIZE = 16;
    private static final int TEXTURE_WIDTH = 32;
    private static final int TEXTURE_HEIGHT = 16;

    private final int u;

    private TrajectoryModeIcon(int u) {
        super(0, 0);
        this.u = u;
    }

    static AllIcons of(TrajectoryMode mode) {
        return mode == TrajectoryMode.HIGH ? Holder.HIGH : Holder.FLAT;
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(TEXTURE, x, y, 0, u, 0, SIZE, SIZE, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int color) {
        VertexConsumer builder = buffer.getBuffer(RenderType.text(TEXTURE));
        Matrix4f matrix = poseStack.last().pose();
        float u1 = (float) u / TEXTURE_WIDTH;
        float u2 = (float) (u + SIZE) / TEXTURE_WIDTH;
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        vertex(builder, matrix, 0.0F, 0.0F, red, green, blue, u1, 0.0F);
        vertex(builder, matrix, 0.0F, 1.0F, red, green, blue, u1, 1.0F);
        vertex(builder, matrix, 1.0F, 1.0F, red, green, blue, u2, 1.0F);
        vertex(builder, matrix, 1.0F, 0.0F, red, green, blue, u2, 0.0F);
    }

    private static void vertex(VertexConsumer builder, Matrix4f matrix, float x, float y,
                               int red, int green, int blue, float u, float v) {
        builder.addVertex(matrix, x, y, 0.0F).setColor(red, green, blue, 255).setUv(u, v)
                .setLight(LightTexture.FULL_BRIGHT);
    }

    /** Иконки создаются при первом обращении, уже на клиентском потоке отрисовки. */
    private static final class Holder {
        private static final AllIcons FLAT = new TrajectoryModeIcon(0);
        private static final AllIcons HIGH = new TrajectoryModeIcon(SIZE);
    }
}
