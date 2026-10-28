package com.electroradar.client.monitor;

import java.io.IOException;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.electroradar.ElectroRadar;

/** Пакет атласа GUI с цветом и прозрачностью каждой вершины, без сортировки меток. */
final class MonitorGuiRenderTypes extends RenderType {
    private static ShaderInstance blipShader;
    static final RenderType BLIPS = create("electroradar_gui_blips",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536,
            CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> blipShader))
                    .setTextureState(new TextureStateShard(MonitorBlipSprite.TEXTURE, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

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
