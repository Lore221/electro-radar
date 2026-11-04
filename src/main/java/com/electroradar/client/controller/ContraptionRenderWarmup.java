package com.electroradar.client.controller;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;

import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.controller.RadarControllerContent;

/**
 * Прогревает вложенную отрисовку Flywheel в первых кадрах мира, до ручной сборки радара.
 * Effect не создаёт игровых объектов: нулевые матрицы оставляют экземпляры в пакетах
 * отрисовки для компиляции программ, но не дают видимой геометрии.
 * Отключение: {@code -Delectroradar.client.contraptionWarmup=false}.
 */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class ContraptionRenderWarmup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("electroradar.client.contraptionWarmup", "true"));

    private static VisualizationManager warmedManager;
    private static WarmupEffect pending;
    private static int preparedFrames;

    private ContraptionRenderWarmup() { }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!ENABLED) return;
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManager manager = VisualizationManager.get(level);
        // Менеджер привязан к миру и пересоздаётся Flywheel при смене backend/reload.
        // Его экземпляр служит ключом прогрева; отключённый backend возвращает null.
        if (manager == warmedManager) return;
        warmedManager = manager;
        pending = null;
        preparedFrames = 0;
        if (manager != null) {
            pending = new WarmupEffect(level);
            manager.effects().queueAdd(pending);
        }
    }

    @SubscribeEvent
    public static void afterLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL
                || pending == null || !pending.prepared) return;
        // Первый проход знакомит Flywheel со всеми материалами. Новый cutout/fog source
        // может сбросить программы, собранные ранее в том же кадре, поэтому нужен второй.
        if (++preparedFrames < 2) return;
        // Удалять можно лишь после реальной отрисовки: один Models.block() не компилирует
        // графические программы. Flywheel обработает удаление в следующем своём проходе.
        warmedManager.effects().queueRemove(pending);
        pending = null;
    }

    private static final class WarmupEffect implements Effect {
        private final ClientLevel level;
        // Создание visual может выполняться рабочим потоком Flywheel.
        private volatile boolean prepared;

        private WarmupEffect(ClientLevel level) { this.level = level; }

        @Override
        public ClientLevel level() { return level; }

        @Override
        public EffectVisual<?> visualize(VisualizationContext context, float partialTick) {
            WarmupVisual visual = new WarmupVisual();
            try {
                visual.prepare(context);
            } catch (RuntimeException exception) {
                visual.delete();
                LOGGER.warn("Could not prepare optional contraption render warmup", exception);
            } finally {
                prepared = true;
            }
            return visual;
        }
    }

    private static final class WarmupVisual implements EffectVisual<WarmupEffect> {
        private final List<TransformedInstance> instances = new ArrayList<>(2);
        private VisualEmbedding embedding;

        private void prepare(VisualizationContext context) {
            // Как ContraptionVisual, используем TRANSFORMED внутри embedding: обычная
            // отрисовка блока прогрела бы другой (DEFAULT) контекст программы.
            embedding = context.createEmbedding(Vec3i.ZERO);
            embedding.transforms(new Matrix4f(), new Matrix3f());
            add(RadarControllerContent.RADAR_PANEL.get().defaultBlockState());
            add(RadarControllerContent.OVERVIEW_MODULE.get().defaultBlockState());
        }

        private void add(BlockState state) {
            // Модели принадлежат кешу Models и удаляются им при reload, а не этим visual.
            TransformedInstance instance = embedding.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, Models.block(state)).createInstance();
            instances.add(instance);
            instance.setZeroTransform();
            instance.setChanged();
        }

        @Override
        public void update(float partialTick) { }

        @Override
        public void delete() {
            instances.forEach(TransformedInstance::delete);
            instances.clear();
            if (embedding != null) {
                embedding.delete();
                embedding = null;
            }
        }
    }
}
