package com.electroradar.client.alarm;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.ElectroRadar;
import com.electroradar.radar.alarm.ProtectionZone;
import com.electroradar.radar.alarm.ShellAlarmBlockEntity;
import com.electroradar.radar.alarm.ZoneResizePayload;
import com.electroradar.radar.alarm.ZoneSelectionItem;

/** Catnip Outliner — только визуализация. Выбор и изменение предмета выполняет сервер. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class ZoneSelectionClientEvents {
    private static final Object SELECTION = new Object();
    private static final Object APPLIED = new Object();
    private static Direction selectedFace;

    private ZoneSelectionClientEvents() { }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        selectedFace = null;
        Outliner.getInstance().remove(SELECTION);
        Outliner.getInstance().remove(APPLIED);
        if (mc.level == null || mc.player == null || mc.screen != null) return;
        InteractionHand hand = activeHand();
        if (hand == null) return;
        ItemStack stack = mc.player.getItemInHand(hand);
        var saved = ZoneSelectionItem.zone(stack);
        if (saved.isPresent() && saved.get().dimension().equals(mc.level.dimension().location())) {
            AABB bounds = saved.get().bounds();
            selectedFace = pointedFace(bounds, mc.player.getEyePosition(), mc.player.getLookAngle());
            Outliner.getInstance().chaseAABB(SELECTION, bounds).colored(0x58BDEB).lineWidth(1 / 16f)
                    .highlightFace(selectedFace);
        } else {
            ZoneSelectionItem.firstCorner(stack, mc.level.dimension().location()).ifPresent(first -> {
                BlockPos target = mc.hitResult instanceof BlockHitResult hit
                        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos()
                        : BlockPos.containing(mc.player.getEyePosition().add(mc.player.getLookAngle().scale(16)));
                try {
                    ProtectionZone preview = new ProtectionZone(mc.level.dimension().location(), first, target);
                    Outliner.getInstance().chaseAABB(SELECTION, preview.bounds()).colored(0xE7B74F).lineWidth(1 / 16f);
                } catch (IllegalArgumentException outsideLimits) { /* Размер подтверждает сервер. */ }
            });
        }
        if (mc.hitResult instanceof BlockHitResult hit && mc.level.hasChunkAt(hit.getBlockPos())
                && mc.level.getBlockEntity(hit.getBlockPos()) instanceof ShellAlarmBlockEntity alarm
                && alarm.zone() != null && alarm.zone().dimension().equals(mc.level.dimension().location())) {
            Outliner.getInstance().chaseAABB(APPLIED, alarm.zone().bounds()).colored(0x70D680).lineWidth(1 / 24f);
        }
    }

    @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !Screen.hasControlDown()) return;
        InteractionHand hand = activeHand();
        if (hand == null || selectedFace == null || event.getScrollDeltaY() == 0) return;
        event.setCanceled(true);
        ZoneSelectionItem.selectionId(mc.player.getItemInHand(hand)).ifPresent(id ->
                PacketDistributor.sendToServer(new ZoneResizePayload(hand, id, selectedFace, event.getScrollDeltaY() > 0 ? 1 : -1)));
    }

    private static InteractionHand activeHand() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        if (player.getMainHandItem().getItem() instanceof ZoneSelectionItem) return InteractionHand.MAIN_HAND;
        return player.getOffhandItem().getItem() instanceof ZoneSelectionItem ? InteractionHand.OFF_HAND : null;
    }

    private static Direction pointedFace(AABB box, Vec3 start, Vec3 look) {
        Vec3 end = start.add(look.scale(96));
        var hit = box.contains(start) ? box.clip(end, start) : box.clip(start, end);
        if (hit.isEmpty()) return null;
        Vec3 p = hit.get();
        double[] distances = {Math.abs(p.y - box.minY), Math.abs(p.y - box.maxY),
                Math.abs(p.z - box.minZ), Math.abs(p.z - box.maxZ), Math.abs(p.x - box.minX), Math.abs(p.x - box.maxX)};
        int nearest = 0;
        for (int i = 1; i < distances.length; i++) if (distances[i] < distances[nearest]) nearest = i;
        return Direction.from3DDataValue(nearest);
    }
}
