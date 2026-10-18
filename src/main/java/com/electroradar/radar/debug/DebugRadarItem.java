package com.electroradar.radar.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkItemBinding;

/**
 * Предмет временного радара: позволяет привязать стопку к сети и передать эту связь новым блокам.
 * NetworkId хранится в CUSTOM_DATA самого предмета; данные других систем в этом компоненте
 * остаются нетронутыми, а runtime-токены и состояние сети никогда не копируются.
 */
public final class DebugRadarItem extends BlockItem {
    public DebugRadarItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * При ПКМ по уже установленному тестовому радару перепривязывает предмет и завершает действие.
     * Клиент возвращает успешный результат для завершения взаимодействия, не читая BlockEntity
     * и не меняя ItemStack; чтение ID, запись компонента и сообщение игроку выполняются сервером.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPosition = context.getClickedPos();
        if (!level.getBlockState(clickedPosition).is(DebugRadarContent.DEBUG_RADAR_BLOCK.get())) {
            return super.useOn(context);
        }
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(level instanceof ServerLevel)) {
            return InteractionResult.CONSUME;
        }

        EndpointKey.Static endpoint = new EndpointKey.Static(level.dimension(), clickedPosition);
        BlockEntity blockEntity = level.getBlockEntity(clickedPosition);
        if (!(blockEntity instanceof DebugRadarBlockEntity radar)) {
            RadarDiagnostics.log("radar_item_binding_failed", null, endpoint, null, null,
                    "reason=target_block_entity_missing");
            return InteractionResult.CONSUME;
        }

        NetworkId networkId = radar.getOrCreateNetworkIdForBinding();
        NetworkItemBinding.write(context.getItemInHand(), networkId);
        RadarDiagnostics.log("radar_item_bound", networkId, endpoint, null,
                radar.currentTickForDiagnostics(), "item=debug_radar action=bind_or_replace");

        Player player = context.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.literal("Connected to radar network"), true);
        }
        return InteractionResult.sidedSuccess(false);
    }

    /**
     * Считывает ID до обычного BlockItem.place: vanilla уменьшает стопку и загружает BlockEntity
     * в ходе размещения. Применение после успешного super-вызова безопасно, потому что BlockEntity
     * регистрируется только на первом серверном тике, а onLoad теперь лишь сбрасывает runtime-поля.
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        NetworkId boundNetworkId = NetworkItemBinding.read(context.getItemInHand());
        BlockPos placementPosition = context.getClickedPos();
        InteractionResult result = super.place(context);
        Level level = context.getLevel();
        if (level.isClientSide || !result.consumesAction() || boundNetworkId == null) {
            return result;
        }

        EndpointKey.Static endpoint = new EndpointKey.Static(level.dimension(), placementPosition);
        BlockEntity blockEntity = level.getBlockEntity(placementPosition);
        if (blockEntity instanceof DebugRadarBlockEntity radar) {
            radar.applyNetworkIdFromItem(boundNetworkId);
            return result;
        }

        RadarDiagnostics.log("radar_item_network_id_apply_failed", boundNetworkId, endpoint, null, null,
                "reason=placed_block_entity_missing");
        return result;
    }

}
