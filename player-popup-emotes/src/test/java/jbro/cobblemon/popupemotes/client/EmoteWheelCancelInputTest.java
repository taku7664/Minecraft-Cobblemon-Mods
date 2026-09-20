package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmoteWheelCancelInputTest {
    @Test
    void escapeOrRightClickRequestsCancellation() {
        assertTrue(EmoteWheelCancelInput.requested(true, false));
        assertTrue(EmoteWheelCancelInput.requested(false, true));
        assertTrue(EmoteWheelCancelInput.requested(true, true));
    }

    @Test
    void noCancelInputKeepsTheWheelOpen() {
        assertFalse(EmoteWheelCancelInput.requested(false, false));
    }
}
