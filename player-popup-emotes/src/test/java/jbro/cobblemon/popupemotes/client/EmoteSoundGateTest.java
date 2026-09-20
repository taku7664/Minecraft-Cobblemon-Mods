package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmoteSoundGateTest {
    @Test
    void waitsUntilTheEmoteAndPlayerAreReadyThenPlaysOnce() {
        EmoteSoundGate gate = new EmoteSoundGate();

        assertFalse(gate.shouldPlay(false, false));
        assertFalse(gate.shouldPlay(true, false));
        assertFalse(gate.shouldPlay(false, true));
        assertTrue(gate.shouldPlay(true, true));
        assertFalse(gate.shouldPlay(true, true));
    }
}
