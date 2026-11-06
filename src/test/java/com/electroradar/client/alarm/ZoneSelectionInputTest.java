package com.electroradar.client.alarm;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.event.InputEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneSelectionInputTest {
    @Test void offhandSelectorHandlesTheInitialMainHandEventWithoutVanillaFallthrough() {
        ZoneSelectionInput input = new ZoneSelectionInput();
        List<InteractionHand> sent = new ArrayList<>();
        var event = use();
        input.use(event, InteractionHand.OFF_HAND, sent::add);
        assertTrue(event.isCanceled());
        assertFalse(event.shouldSwingHand());
        assertEquals(List.of(InteractionHand.OFF_HAND), sent);
    }

    @Test void holdingUseCannotFixBothCornersButAnotherPressCan() {
        ZoneSelectionInput input = new ZoneSelectionInput();
        List<InteractionHand> sent = new ArrayList<>();
        input.use(use(), InteractionHand.MAIN_HAND, sent::add);
        input.releaseIfKeyUp(true);
        var repeat = use();
        input.use(repeat, InteractionHand.MAIN_HAND, sent::add);
        assertTrue(repeat.isCanceled());
        assertEquals(1, sent.size());
        input.releaseIfKeyUp(false);
        input.use(use(), InteractionHand.MAIN_HAND, sent::add);
        assertEquals(2, sent.size());
    }

    @Test void attackAndPickBlockKeepTheirNormalBehavior() {
        ZoneSelectionInput input = new ZoneSelectionInput();
        for (int button : new int[] {0, 2}) {
            var event = new InputEvent.InteractionKeyMappingTriggered(button, null, InteractionHand.MAIN_HAND);
            input.use(event, InteractionHand.MAIN_HAND, hand -> fail("not a selection action"));
            assertFalse(event.isCanceled());
        }
    }

    private static InputEvent.InteractionKeyMappingTriggered use() {
        return new InputEvent.InteractionKeyMappingTriggered(1, null, InteractionHand.MAIN_HAND);
    }
}
