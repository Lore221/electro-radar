package com.electroradar.radar.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Стандартный предмет сетевого блока: копирование NetworkId по ПКМ, outline в руках и
 * перенос привязки при установке. Новый источник использует этот класс и контракт
 * NetworkIdBindingTarget; добавлять его тип в обработчики предметов и linker не требуется.
 */
public class NetworkBoundBlockItem extends BlockItem implements NetworkOutlineActivatingItem {
    private final String bindingMessageKey;

    public NetworkBoundBlockItem(Block block, Properties properties) {
        this(block, properties, "message.electroradar.network.item_bound");
    }

    public NetworkBoundBlockItem(Block block, Properties properties, String bindingMessageKey) {
        super(block, properties);
        this.bindingMessageKey = bindingMessageKey;
    }

    /** Привязка имеет приоритет над собственным GUI и действиями нового блока-источника. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return NetworkItemBindingInteraction.copyFromSource(context, bindingMessageKey);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = NetworkItemBindingInteraction.copyFromSource(context, bindingMessageKey);
        return result == InteractionResult.PASS ? super.useOn(context) : result;
    }

    /** ID сохраняется до vanilla-размещения, которое может израсходовать последний предмет. */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        NetworkId networkId = NetworkItemBinding.read(context.getItemInHand());
        BlockPos position = context.getClickedPos();
        InteractionResult result = super.place(context);
        Level level = context.getLevel();
        if (!level.isClientSide && result.consumesAction()) {
            BlockEntity placed = level.getBlockEntity(position);
            if (placed != null) initializePlacedBlock(placed, networkId);
        }
        return result;
    }

    /** Специализация потребителя может дополнительно создать идентичность физической сборки. */
    protected void initializePlacedBlock(BlockEntity placed, NetworkId networkId) {
        if (networkId != null && placed instanceof NetworkIdBindingTarget target) {
            target.applyNetworkIdFromItem(networkId);
        }
    }
}
