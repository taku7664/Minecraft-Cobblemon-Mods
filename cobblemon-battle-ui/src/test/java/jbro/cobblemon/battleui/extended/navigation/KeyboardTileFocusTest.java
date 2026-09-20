package jbro.cobblemon.battleui.extended.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeyboardTileFocusTest {
    @AfterEach
    void resetInputOwnership() {
        KeyboardTileFocus.mouseMoved();
    }

    @Test
    void keyboardInputSuppressesStationaryMouseHover() {
        Object tile = new Object();
        KeyboardTileFocus.mouseMoved();

        KeyboardTileFocus.set(tile);

        assertTrue(KeyboardTileFocus.isFocused(tile));
        assertFalse(KeyboardTileFocus.allowsMouseHover());
    }

    @Test
    void actualMouseMovementTakesOwnershipBack() {
        Object tile = new Object();
        KeyboardTileFocus.set(tile);

        KeyboardTileFocus.mouseMoved();

        assertFalse(KeyboardTileFocus.isFocused(tile));
        assertTrue(KeyboardTileFocus.allowsMouseHover());
    }

    @Test
    void resolvesFocusedTileIndexForKeyboardTooltip() {
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        KeyboardTileFocus.set(second);

        assertEquals(1, KeyboardTileFocus.focusedIndex(List.of(first, second, third)));

        KeyboardTileFocus.clear();
        assertEquals(-1, KeyboardTileFocus.focusedIndex(List.of(first, second, third)));
    }
}
