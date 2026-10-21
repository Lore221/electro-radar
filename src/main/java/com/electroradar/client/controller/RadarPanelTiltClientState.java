package com.electroradar.client.controller;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import com.electroradar.radar.controller.RadarControllerBlock;
import com.electroradar.radar.controller.RadarPanelContraption;
import com.electroradar.radar.controller.RadarPanelTiltPayload;

/** Клиентская интерполяция Create угла. Этот класс не выполняет поиск целей и не меняет мир. */
public final class RadarPanelTiltClientState {
    private static final Map<Integer, Animation> ANIMATIONS = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long clientTicks;

    private RadarPanelTiltClientState() {
    }

    public static void accept(RadarPanelTiltPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        synchronizeLevel(level);
        if (level == null) return;
        int duration = Math.clamp(payload.remainingTicks(), 0, 20);
        if (duration == 0) {
            ANIMATIONS.remove(payload.entityId());
            if (level.getEntity(payload.entityId()) instanceof ControlledContraptionEntity entity
                    && hasLoadedController(level, entity)) {
                entity.setAngle(payload.targetAngle());
            }
            return;
        }
        ANIMATIONS.put(payload.entityId(), new Animation(payload.currentAngle(), payload.targetAngle(),
                duration, clientTicks));
        if (level.getEntity(payload.entityId()) instanceof ControlledContraptionEntity entity
                && hasLoadedController(level, entity)) {
            entity.setAngle(payload.currentAngle());
        }
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        synchronizeLevel(level);
        if (level == null || ANIMATIONS.isEmpty()) return;
        clientTicks++;
        Iterator<Map.Entry<Integer, Animation>> iterator = ANIMATIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Animation> entry = iterator.next();
            Animation animation = entry.getValue();
            int elapsed = (int) Math.min(animation.duration(), clientTicks - animation.startedAt());
            if (level.getEntity(entry.getKey()) instanceof ControlledContraptionEntity entity) {
                // Серверный runtime останавливается вместе с контроллером. Клиент тоже прекращает
                // свою локальную интерполяцию, если Create entity осталась без загруженного владельца.
                if (!hasLoadedController(level, entity)) {
                    iterator.remove();
                    continue;
                }
                float amount = elapsed / (float) animation.duration();
                entity.setAngle(animation.startAngle()
                        + (animation.targetAngle() - animation.startAngle()) * amount);
            }
            if (elapsed >= animation.duration()) iterator.remove();
        }
    }

    private static void synchronizeLevel(ClientLevel level) {
        if (level == currentLevel) return;
        ANIMATIONS.clear();
        currentLevel = level;
        clientTicks = 0L;
    }

    private static boolean hasLoadedController(ClientLevel level, ControlledContraptionEntity entity) {
        if (!(entity.getContraption() instanceof RadarPanelContraption)) return false;
        BlockPos controllerPos = entity.blockPosition().below();
        return level.hasChunkAt(controllerPos)
                && level.getBlockState(controllerPos).getBlock() instanceof RadarControllerBlock;
    }

    private record Animation(float startAngle, float targetAngle, int duration, long startedAt) {
    }
}
