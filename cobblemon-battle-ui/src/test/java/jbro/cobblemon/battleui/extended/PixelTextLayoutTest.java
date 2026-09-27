package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class PixelTextLayoutTest {
    @Test
    void nestedFontScalePreservesTheOversampledFontStrokes() {
        float parent = .426f;
        float gui = 2f;
        assertEquals(1.5f, PixelTextLayout.fontScale(.85f, parent, gui) * parent * gui, .0001f);
    }

    @Test
    void allModalFontSizesUseReadableHalfOrWholePhysicalScale() {
        for (float parent : new float[]{.426f, .816f, 1.25f}) {
            for (float gui : new float[]{1f, 2f, 3f, 4f}) {
                for (float requested : new float[]{.85f, .9f, .95f, 1f, 1.05f, 1.15f, 1.2f}) {
                    float effective = PixelTextLayout.fontScale(requested, parent, gui) * parent * gui;
                    assertTrue(effective >= 1.4999f);
                    assertEquals(Math.round(effective * 2), effective * 2, .0001f);
                }
            }
        }
    }

    @Test
    void originSnapsAfterBothParentTransformAndGuiScale() {
        float snapped = PixelTextLayout.snap(17.25f, 11.3f, .426f, 2f);
        float physical = (snapped * .426f + 11.3f) * 2f;
        assertEquals(Math.round(physical), physical, .0001f);
    }
}
