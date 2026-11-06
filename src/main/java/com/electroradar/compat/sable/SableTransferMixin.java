package com.electroradar.compat.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class SableTransferMixin {
    @WrapMethod(method = "moveBlocks", remap = false)
    private static void electroRadar$move(ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform,
                                          Iterable<BlockPos> blocks, Operation<Void> original) {
        SableTransferBridge.move(level, transform, blocks, positions -> original.call(level, transform, positions));
    }
}
