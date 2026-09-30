package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class PixelTextLayoutTest {
    @Test
    void textLandsOnAWholeMultipleOfTheGameTextSize() {
        for (float parent : new float[]{.426f, .816f, 1f, 1.25f, 2f}) {
            for (float requested : new float[]{.6f, .7f, .85f, 1f, 1.2f, 1.6f, 2.2f}) {
                float effective = PixelTextLayout.fontScale(requested, parent, 2f) * parent;
                assertTrue(effective >= .9999f, "never below the game's text size");
                assertEquals(Math.round(effective), effective, .0001f);
            }
        }
    }

    @Test
    void smallRequestsDrawAtTheGameTextSize() {
        assertEquals(1f, PixelTextLayout.crisp(.7f));
        assertEquals(1f, PixelTextLayout.crisp(1.2f));
        assertEquals(2f, PixelTextLayout.crisp(1.6f));
    }

    @Test
    void originSnapsAfterBothParentTransformAndGuiScale() {
        float snapped = PixelTextLayout.snap(17.25f, 11.3f, .426f, 2f);
        float physical = (snapped * .426f + 11.3f) * 2f;
        assertEquals(Math.round(physical), physical, .0001f);
    }
}
