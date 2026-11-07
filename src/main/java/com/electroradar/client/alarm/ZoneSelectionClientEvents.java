package com.electroradar.client.alarm;

import java.util.UUID;
import org.lwjgl.glfw.GLFW;
import com.simibubi.create.AllKeys;
import com.simibubi.create.foundation.utility.RaycastHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
import com.electroradar.radar.alarm.ZoneSelectPayload;
import com.electroradar.radar.alarm.ZoneSelectionItem;
import com.electroradar.radar.alarm.ZoneSelectionTargeting;

/** Input intent goes to the server; Catnip owns outline ticking and frame interpolation. */
@EventBusSubscriber(modid = ElectroRadar.MOD_ID, value = Dist.CLIENT)
public final class ZoneSelectionClientEvents {
    private static final ZoneSelectionOutlines OUTLINES = new ZoneSelectionOutlines(Outliner.getInstance());
    private static Object lastLevel;
    private static InteractionHand lastHand;
    private static int lastSlot = -1;
    private static UUID lastSelection;
    private static int range = 10;
    private static final ZoneSelectionInput INPUT = new ZoneSelectionInput();

    private ZoneSelectionClientEvents() { }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        INPUT.releaseIfKeyUp(mc.options.keyUse.isDown());
        InteractionHand hand = activeHand();
        if (mc.level == null || mc.player == null || mc.screen != null || hand == null) {
            OUTLINES.clear();
            lastLevel = null;
            lastHand = null;
            lastSelection = null;
            range = 10;
            return;
        }
        ItemStack stack = mc.player.getItemInHand(hand);
        UUID id = ZoneSelectionItem.selectionId(stack).orElse(null);
        int slot = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().selected : -1;
        if (lastLevel != mc.level || lastHand != hand || lastSlot != slot
                || lastSelection != null && !lastSelection.equals(id)) {
            OUTLINES.clear();
            range = 10;
        }
        lastLevel = mc.level;
        lastHand = hand;
        lastSlot = slot;
        lastSelection = id;
        AABB selection = null;
        AABB applied = null;
        Direction face = null;
        int color = 0x58BDEB;
        var saved = ZoneSelectionItem.zone(stack);
        if (saved.isPresent()) {
            if (saved.get().dimension().equals(mc.level.dimension().location())) {
                selection = saved.get().bounds();
                face = pointedFace(selection);
            }
        } else {
            BlockPos target = ZoneSelectionTargeting.target(mc.player, hand, AllKeys.ACTIVATE_TOOL.isPressed(), range);
            var first = ZoneSelectionItem.firstCorner(stack, mc.level.dimension().location());
            if (first.isPresent()) {
                try {
                    selection = new ProtectionZone(mc.level.dimension().location(), first.get(),
                            target == null ? first.get() : target).bounds();
                } catch (IllegalArgumentException outsideLimits) { /* Server validates the final zone. */ }
            } else if (target != null) selection = new AABB(target);
            color = 0xE7B74F;
        }
        if (mc.hitResult instanceof BlockHitResult hit && mc.level.hasChunkAt(hit.getBlockPos())
                && mc.level.getBlockEntity(hit.getBlockPos()) instanceof ShellAlarmBlockEntity alarm
                && alarm.zone() != null && alarm.zone().dimension().equals(mc.level.dimension().location())) {
            applied = alarm.zone().bounds();
        }
        OUTLINES.update(selection, face, color, applied);
    }

    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        InteractionHand hand = activeHand();
        if (mc.player == null || mc.level == null || mc.screen != null || hand == null) return;
        // Предмет основной руки важнее Выделителя во второй, как в vanilla: иначе с ним в левой руке
        // нельзя поставить блок. Вторая рука получит своё событие, если основная ничего не сделает.
        if (hand == InteractionHand.OFF_HAND && event.getHand() == InteractionHand.MAIN_HAND
                && !mc.player.getMainHandItem().isEmpty()) return;
        INPUT.use(event, hand, selectedHand -> PacketDistributor.sendToServer(new ZoneSelectPayload(selectedHand,
                ZoneSelectionItem.selectionId(mc.player.getItemInHand(selectedHand)),
                AllKeys.ACTIVATE_TOOL.isPressed(), range, mc.player.isShiftKeyDown())));
    }

    @SubscribeEvent public static void mouseReleased(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_RELEASE
                && Minecraft.getInstance().options.keyUse.matchesMouse(event.getButton())) INPUT.releaseIfKeyUp(false);
    }

    @SubscribeEvent public static void keyReleased(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_RELEASE
                && Minecraft.getInstance().options.keyUse.matches(event.getKey(), event.getScanCode())) INPUT.releaseIfKeyUp(false);
    }

    @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || !AllKeys.ctrlDown()
                || !Double.isFinite(event.getScrollDeltaY()) || event.getScrollDeltaY() == 0) return;
        InteractionHand hand = activeHand();
        if (hand == null) return;
        event.setCanceled(true);
        ItemStack stack = mc.player.getItemInHand(hand);
        var saved = ZoneSelectionItem.zone(stack);
        if (saved.isEmpty()) {
            range = ZoneSelectionControls.range(range, event.getScrollDeltaY());
            return;
        }
        if (!saved.get().dimension().equals(mc.level.dimension().location())) return;
        AABB bounds = saved.get().bounds();
        Direction face = pointedFace(bounds);
        if (face == null) return;
        boolean inside = bounds.contains(mc.gameRenderer.getMainCamera().getPosition());
        int step = ZoneSelectionControls.resizeStep(event.getScrollDeltaY(), inside);
        ZoneSelectionItem.selectionId(stack).ifPresent(id ->
                PacketDistributor.sendToServer(new ZoneResizePayload(hand, id, face, step)));
    }

    private static InteractionHand activeHand() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        if (player.getMainHandItem().getItem() instanceof ZoneSelectionItem) return InteractionHand.MAIN_HAND;
        return player.getOffhandItem().getItem() instanceof ZoneSelectionItem ? InteractionHand.OFF_HAND : null;
    }

    private static Direction pointedFace(AABB bounds) {
        Minecraft mc = Minecraft.getInstance();
        AABB hitbox = bounds.inflate(.45);
        boolean inside = hitbox.contains(mc.gameRenderer.getMainCamera().getPosition());
        var hit = RaycastHelper.rayTraceUntil(mc.player, 70,
                pos -> inside ^ hitbox.contains(VecHelper.getCenterOf(pos)));
        return hit.missed() ? null : inside ? hit.getFacing().getOpposite() : hit.getFacing();
    }
}
