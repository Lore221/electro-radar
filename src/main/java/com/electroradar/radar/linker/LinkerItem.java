package com.electroradar.radar.linker;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import com.electroradar.radar.alarm.ShellAlarmBlockEntity;
import com.electroradar.radar.network.NetworkKind;
import net.minecraft.world.level.Level;

import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkItemBinding;
import com.electroradar.radar.network.NetworkLinkTarget;
import com.electroradar.radar.network.NetworkOutlineActivatingItem;

/**
 * Копирует и применяет NetworkId по правилам Power Radar: ПКМ — копирование/подключение,
 * Shift+ПКМ по устройству — отключение, Shift+ПКМ в воздух — очистка выбора.
 * Состояние принадлежит ItemStack; статических списков устройств и собственного tick нет.
 */
public final class LinkerItem extends Item implements NetworkOutlineActivatingItem {
    public LinkerItem(Properties properties) {
        super(properties);
    }

    /** NeoForge вызывает этот hook до GUI монитора и других взаимодействий блока. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return useOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || !level.hasChunkAt(context.getClickedPos())
                || !(level.getBlockEntity(context.getClickedPos()) instanceof NetworkLinkTarget member)) {
            return InteractionResult.PASS;
        }
        if (!player.mayBuild() || !level.mayInteract(player, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        // На клиент не передаётся NetworkId блоков: он только предсказывает успешный жест.
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        NetworkLinkTarget target = member.loadedLinkTarget();
        ItemStack held = context.getItemInHand();
        String address = level.dimension().location() + ":" + context.getClickedPos().asLong();
        var data = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        LinkerSelection previous = new LinkerSelection(data.getString("electroradar:link_target"),
                data.getBoolean("electroradar:link_interception") ? NetworkKind.INTERCEPTION : NetworkKind.RADAR);
        LinkerSelection next = previous.click(address, target instanceof ShellAlarmBlockEntity, player.isShiftKeyDown());
        CustomData.update(DataComponents.CUSTOM_DATA, held, tag -> {
            tag.putString("electroradar:link_target", next.target());
            tag.putBoolean("electroradar:link_interception", next.kind() == NetworkKind.INTERCEPTION);
        });
        if (target == null) {
            message(player, "unloaded");
        } else if (player.isShiftKeyDown()) {
            message(player, target.reconfigureNetwork(null) ? (target.networkKindForLinking() == NetworkKind.INTERCEPTION ? "interception_disconnected" : "radar_disconnected") : "unloaded");
        } else {
            if (target instanceof ShellAlarmBlockEntity alarm && next.kind() == NetworkKind.INTERCEPTION) {
                NetworkItemBinding.write(held, alarm.getOrCreateInterceptionNetwork(), NetworkKind.INTERCEPTION);
                message(player, "interception_copied");
            } else configure(held, player, target);
        }
        return InteractionResult.sidedSuccess(false);
    }

    private static void configure(ItemStack stack, Player player, NetworkLinkTarget target) {
        NetworkId selected = NetworkItemBinding.read(stack);
        NetworkId current = target.networkIdForLinking();
        NetworkKind kind = target.networkKindForLinking();
        if (selected != null && NetworkItemBinding.kind(stack) != kind) {
            // Returning to the radar port on the same Alarm also switches the copied selection.
            if (target instanceof ShellAlarmBlockEntity && current != null) {
                NetworkItemBinding.write(stack, current, kind);
                message(player, "radar_copied");
            } else message(player, "wrong_network");
            return;
        }
        if (selected == null) {
            if (current == null) {
                message(player, "radar_missing");
            } else {
                NetworkItemBinding.write(stack, current, kind);
                message(player, kind == NetworkKind.INTERCEPTION ? "interception_copied" : "radar_copied");
            }
        } else if (selected.equals(current)) {
            message(player, kind == NetworkKind.INTERCEPTION ? "interception_already_connected" : "radar_already_connected");
        } else {
            message(player, target.reconfigureNetwork(selected) ? (kind == NetworkKind.INTERCEPTION ? "interception_applied" : "radar_applied") : "unloaded");
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            NetworkItemBinding.clear(stack);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.remove("electroradar:link_target"); tag.remove("electroradar:link_interception");
            });
            message(player, "cleared");
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return NetworkItemBinding.read(stack) != null || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.electroradar.linker.description").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.electroradar.linker.alarm_ports").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.electroradar.linker.configure").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.electroradar.linker.disconnect").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.electroradar.linker.clear").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void message(Player player, String suffix) {
        player.displayClientMessage(Component.translatable("message.electroradar.linker." + suffix), true);
    }
}
