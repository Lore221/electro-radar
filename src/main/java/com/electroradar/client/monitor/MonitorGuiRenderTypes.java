package com.electroradar.client.monitor;

import java.io.IOException;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.electroradar.ElectroRadar;

/** Пакет атласа GUI с цветом и прозрачностью каждой вершины, без сортировки меток. */
final class MonitorGuiRenderTypes extends RenderType {
    @Nullable
    private static ShaderInstance blipShader;
    private static final RenderType BLIPS = create("electroradar_gui_blips",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536,
            CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> blipShader))
                    .setTextureState(new TextureStateShard(MonitorBlipSprite.TEXTURE, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    /** До успешной загрузки программы пропускаем только метки, сохраняя остальные слои GUI. */
    @Nullable
    static RenderType blipsIfReady() {
        return blipShader == null ? null : BLIPS;
    }

    /**
     * В подключённом Minecraft 1.21.1 {@code position_tex_color.fsh} отсекает {@code texture * vertexColor}
     * при {@code alpha < 0.1}. Это обрывает последние 10% затухания метки. Наша программа отсекает
     * только прозрачные тексели атласа, затем применяет цвет и полную alpha вершины.
     * Ссылку обновляет штатный callback успешной загрузки, в том числе при F3+T.
     */
    static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ElectroRadar.MOD_ID, "monitor_blips"),
                DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> blipShader = loaded);
    }

    // Наследование даёт фабрике доступ к штатным состояниям RenderType; экземпляры не нужны.
    private MonitorGuiRenderTypes() {
        super("electroradar_gui_blips", DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS, 1536, false, false, () -> { }, () -> { });
    }
}
