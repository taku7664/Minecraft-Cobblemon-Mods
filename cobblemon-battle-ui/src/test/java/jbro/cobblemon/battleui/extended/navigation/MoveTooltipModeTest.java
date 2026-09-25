package jbro.cobblemon.battleui.extended.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MoveTooltipModeTest {
    @Test
    void keyboardModeStaysOnForSameSelectionAndResetsForNextOne() {
        MoveTooltipMode mode = new MoveTooltipMode();
        Object firstSelection = new Object();
        Object nextSelection = new Object();

        mode.observe(firstSelection);
        assertFalse(mode.isEnabled());
        mode.setEnabled(firstSelection, true);
        assertTrue(mode.isEnabled());
        mode.observe(firstSelection);
        assertTrue(mode.isEnabled());
        mode.setEnabled(firstSelection, false);
        assertFalse(mode.isEnabled());
        mode.setEnabled(firstSelection, true);
        mode.observe(nextSelection);
        assertFalse(mode.isEnabled());
    }
}
