package com.electroradar.compat.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.command.SableAssembleCommands", remap = false)
public abstract class SableCommandGuardMixin {
    @WrapMethod(method = "executeAssembleAreaCommand", remap = false)
    private static int electroRadar$area(CommandContext<CommandSourceStack> context, Operation<Integer> original) {
        try { return original.call(context); }
        catch (SableTransferRejected rejected) { return fail(context); }
    }
    @WrapMethod(method = {"executeAssembleCubeCommand", "executeAssembleSphereCommand"}, remap = false)
    private static int electroRadar$shape(CommandContext<CommandSourceStack> context, BlockPos center, Operation<Integer> original) {
        try { return original.call(context, center); }
        catch (SableTransferRejected rejected) { return fail(context); }
    }
    @WrapMethod(method = "executeAssembleConnectedCommand", remap = false)
    private static int electroRadar$connected(CommandContext<CommandSourceStack> context, BlockPos start,
                                               int maximum, Operation<Integer> original) {
        try { return original.call(context, start, maximum); }
        catch (SableTransferRejected rejected) { return fail(context); }
    }
    @WrapMethod(method = "shatterBlockToSubLevel", remap = false)
    private static boolean electroRadar$shatter(ServerLevel level, BlockPos position, Operation<Boolean> original) {
        try { return original.call(level, position); }
        catch (SableTransferRejected rejected) { return false; }
    }
    private static int fail(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.translatable("message.electroradar.assembly.transfer_unavailable"));
        return 0;
    }
}
