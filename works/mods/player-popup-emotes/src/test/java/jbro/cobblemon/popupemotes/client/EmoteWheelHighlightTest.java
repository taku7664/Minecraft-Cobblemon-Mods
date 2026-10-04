package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmoteWheelHighlightTest {
    private static final int BASE = 0xD82A303C;

    @Test
    void selectionAndHoverHaveIndependentColors() {
        int selected = EmoteWheelHighlight.colorFor(1, 1, 2, BASE);
        int hovered = EmoteWheelHighlight.colorFor(2, 1, 2, BASE);
        int selectedAndHovered = EmoteWheelHighlight.colorFor(1, 1, 1, BASE);

        assertNotEquals(BASE, selected);
        assertNotEquals(BASE, hovered);
        assertNotEquals(selected, hovered);
        assertNotEquals(selected, selectedAndHovered);
        assertNotEquals(hovered, selectedAndHovered);
        assertEquals(BASE, EmoteWheelHighlight.colorFor(0, 1, 2, BASE));
    }

    @Test
    void gameplayAlphaIsApproximatelyHalfOpacity() {
        int adjusted = EmoteWheelHighlight.withAlpha(BASE, 0.55F);
        int alpha = adjusted >>> 24;

        assertTrue(alpha >= 0x70 && alpha <= 0x88);
    }
}
