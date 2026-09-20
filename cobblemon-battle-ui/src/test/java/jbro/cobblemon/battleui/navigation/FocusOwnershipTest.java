package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FocusOwnershipTest {
    @Test
    void stationaryMouseDoesNotStealKeyboardFocus() {
        FocusOwnership focus = new FocusOwnership();
        focus.mouseMoved(10, 10, 1);
        focus.keyboardSelected(3);

        focus.mouseObservedWithoutMovement(1);

        assertEquals(3, focus.selectedIndex());
        assertEquals(InputMethod.KEYBOARD, focus.inputMethod());
    }

    @Test
    void actualMouseMovementTakesFocusBack() {
        FocusOwnership focus = new FocusOwnership();
        focus.mouseMoved(10, 10, 1);
        focus.keyboardSelected(3);

        focus.mouseMoved(11, 10, 2);

        assertEquals(2, focus.selectedIndex());
        assertEquals(InputMethod.MOUSE, focus.inputMethod());
    }
}
