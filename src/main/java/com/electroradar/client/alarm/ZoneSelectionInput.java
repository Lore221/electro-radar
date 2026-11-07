package com.electroradar.client.alarm;

import java.util.function.Consumer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.event.InputEvent;

/** Corner selection is a key press, not Vanilla's repeating item use. */
final class ZoneSelectionInput {
    private boolean held;

    void releaseIfKeyUp(boolean keyDown) { if (!keyDown) held = false; }

    void use(InputEvent.InteractionKeyMappingTriggered event, InteractionHand selectorHand,
            Consumer<InteractionHand> select) {
        if (!event.isUseItem()) return;
        // NeoForge's patched startUseItem returns on cancellation before visiting the next hand.
        // Handle whichever hand contains our selector from this initial event.
        event.setCanceled(true);
        event.setSwingHand(false);
        if (held) return;
        held = true;
        select.accept(selectorHand);
    }
}
