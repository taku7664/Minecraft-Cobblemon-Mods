package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FocusBorderRendererTest {
    @Test
    void fadesBorderWithTheButtonScale() {
        assertEquals(0, FocusBorderRenderer.alphaForScale(1.0));
        assertTrue(FocusBorderRenderer.alphaForScale(1.03) > 0);
        assertTrue(FocusBorderRenderer.alphaForScale(1.03) < 0xF2);
        assertEquals(0xF2, FocusBorderRenderer.alphaForScale(1.06));
    }

    @Test
    void clampsScaleOutsideTheAnimationRange() {
        assertEquals(0, FocusBorderRenderer.alphaForScale(0.9));
        assertEquals(0xF2, FocusBorderRenderer.alphaForScale(1.2));
    }
}
