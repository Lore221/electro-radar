package com.electroradar.radar.alarm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Два ПКМ задают объём; Shift+ПКМ сбрасывает план, Ctrl+колесо двигает выбранную грань. */
public final class ZoneSelectionItem extends Item {
    private static final String DATA = "electroradar:protection_zone";

    public ZoneSelectionItem(Properties properties) { super(properties); }

    public static Optional<ProtectionZone> zone(ItemStack stack) {
        return ProtectionZone.load(data(stack).getCompound("Zone"));
    }

    public static Optional<UUID> selectionId(ItemStack stack) {
        CompoundTag tag = data(stack);
        return tag.hasUUID("SelectionId") ? Optional.of(tag.getUUID("SelectionId")) : Optional.empty();
    }

    public static Optional<BlockPos> firstCorner(ItemStack stack, net.minecraft.resources.ResourceLocation dimension) {
        CompoundTag tag = data(stack);
        return dimension.toString().equals(tag.getString("Dimension")) && tag.contains("First", Tag.TAG_LONG)
                ? Optional.of(BlockPos.of(tag.getLong("First"))) : Optional.empty();
    }

    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) { return useOn(context); }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null || !player.mayBuild() || !level.mayInteract(player, pos)) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ItemStack stack = context.getItemInHand();
        if (player.isShiftKeyDown()) {
            reset(stack);
            message(player, "cleared");
        } else if (level.getBlockEntity(pos) instanceof ShellAlarmBlockEntity alarm) {
            apply(player, stack, alarm);
        } else {
            BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos, false);
            select(stack, player, ZoneSelectionTargeting.block(player, context.getHand(), hit));
        }
        return InteractionResult.CONSUME;
    }

    /** The client sends Ctrl/range intent through ZoneSelectPayload for air selection. */
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.mayBuild()) return InteractionResultHolder.fail(stack);
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) { reset(stack); message(player, "cleared"); }
            else message(player, "no_target");
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static void interact(Player player, InteractionHand hand, Optional<UUID> expectedSelection,
            boolean air, int range, boolean clear) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ZoneSelectionItem) || !player.mayBuild() || player.isSpectator()
                || !(player.level() instanceof ServerLevel level) || range < 1 || range > 100
                || !selectionId(stack).equals(expectedSelection)) return;
        if (clear) { reset(stack); message(player, "cleared"); return; }
        var hit = air ? null : ZoneSelectionTargeting.trace(player);
        if (hit != null && player.canInteractWithBlock(hit.getBlockPos(), 1)
                && level.getBlockEntity(hit.getBlockPos()) instanceof ShellAlarmBlockEntity alarm) {
            if (!level.mayInteract(player, hit.getBlockPos())) return;
            apply(player, stack, alarm);
            return;
        }
        if (zone(stack).isPresent()) { message(player, "ready"); return; }
        BlockPos target = air ? ZoneSelectionTargeting.target(player, hand, true, range,
                        firstCorner(stack, level.dimension().location()))
                : hit == null ? null : ZoneSelectionTargeting.block(player, hand, hit);
        if (target == null) { message(player, "no_target"); return; }
        if (!level.mayInteract(player, target)) return;
        select(stack, player, target);
    }

    private static void apply(Player player, ItemStack stack, ShellAlarmBlockEntity alarm) {
        var selected = zone(stack);
        if (selected.isEmpty()) message(player, "incomplete");
        else message(player, alarm.insertSelector(stack) ? "applied" : "insert_failed");
    }

    private static void select(ItemStack stack, Player player, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)) return;
        if (zone(stack).isPresent()) { message(player, "ready"); return; }
        // Граница мира относится к мировым координатам, а не к собственным координатам структуры.
        boolean onStructure = ProtectionZoneSpace.structureOf(level, pos).isPresent();
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                || !onStructure && !level.getWorldBorder().isWithinBounds(pos)) { message(player, "invalid"); return; }
        var dimension = level.dimension().location();
        Optional<BlockPos> first = firstCorner(stack, dimension);
        if (first.isPresent() && !ProtectionZoneSpace.structureOf(level, first.get())
                .equals(ProtectionZoneSpace.structureOf(level, pos))) {
            message(player, "other_space");
            return;
        }
        if (first.isEmpty()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension.toString());
            tag.putLong("First", pos.asLong());
            tag.putUUID("SelectionId", UUID.randomUUID());
            write(stack, tag);
            message(player, "first");
        } else {
            try {
                store(stack, new ProtectionZone(dimension, first.get(), pos));
                message(player, "saved");
            } catch (IllegalArgumentException invalid) { message(player, "invalid"); }
        }
    }

    public static void resize(Player player, InteractionHand hand, UUID expectedSelection, Direction face, int step) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ZoneSelectionItem) || !player.mayBuild()
                || !(player.level() instanceof ServerLevel level) || (step == 0 || step < -100 || step > 100)
                || !selectionId(stack).filter(expectedSelection::equals).isPresent()) return;
        zone(stack).ifPresent(old -> {
            if (!old.dimension().equals(level.dimension().location())) { message(player, "wrong_dimension"); return; }
            try {
                ProtectionZone next = old.resize(face, step);
                boolean onStructure = ProtectionZoneSpace.structureOf(level, ProtectionZoneSpace.center(old)).isPresent();
                if (!ShellAlarmBlockEntity.validZone(level, next)
                        || !onStructure && (!level.getWorldBorder().isWithinBounds(next.min())
                        || !level.getWorldBorder().isWithinBounds(next.max()))) { message(player, "invalid"); return; }
                store(stack, next);
                player.displayClientMessage(Component.translatable("message.electroradar.zone.size",
                        next.width(), next.height(), next.depth()), true);
            } catch (IllegalArgumentException invalid) { message(player, "invalid"); }
        });
    }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(DATA);
    }

    private static void store(ItemStack stack, ProtectionZone zone) {
        CompoundTag tag = new CompoundTag();
        tag.put("Zone", zone.save());
        tag.putUUID("SelectionId", selectionId(stack).orElseGet(UUID::randomUUID));
        write(stack, tag);
    }

    private static void write(ItemStack stack, CompoundTag tag) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(DATA, tag));
    }

    private static void reset(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(DATA));
    }

    private static void message(Player player, String suffix) {
        player.displayClientMessage(Component.translatable("message.electroradar.zone." + suffix), true);
    }

    @Override public boolean isFoil(ItemStack stack) { return zone(stack).isPresent(); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.electroradar.zone_selector.select").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.electroradar.zone_selector.resize").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.electroradar.zone_selector.apply").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.electroradar.zone_selector.clear").withStyle(ChatFormatting.DARK_GRAY));
        zone(stack).ifPresent(zone -> {
            tooltip.add(Component.translatable("message.electroradar.zone.size", zone.width(), zone.height(), zone.depth()).withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal(zone.dimension() + "  " + zone.min().toShortString() + " → " + zone.max().toShortString())
                    .withStyle(ChatFormatting.DARK_GRAY));
        });
    }
}
