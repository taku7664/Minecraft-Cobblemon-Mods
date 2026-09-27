package jbro.cobblemon.battleui.extended;

import jbro.cobblemon.battleui.extended.ui.shared.BattleSurfaceRenderer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleSurfaceTest {
    @Test void selectedCornersCutOnlyTheirOwnEnd() {
        assertEquals(4, BattleSurfaceRenderer.inset(0, 24, 4, true, false));
        assertEquals(3, BattleSurfaceRenderer.inset(1, 24, 4, true, false));
        assertEquals(0, BattleSurfaceRenderer.inset(23, 24, 4, true, false));
        assertEquals(4, BattleSurfaceRenderer.inset(23, 24, 4, false, true));
    }

    @Test void opacityPreservesRgbAndMultipliesExistingAlpha() {
        assertEquals(0x40123456, BattleSurfaceRenderer.withOpacity(0x80123456, .5f));
        assertEquals(0x00123456, BattleSurfaceRenderer.withOpacity(0x80123456, 0f));
        assertEquals(0x80123456, BattleSurfaceRenderer.withOpacity(0x80123456, 1f));
    }

    @Test void gradientsIncludeBothEndpointsAndInterpolateAlpha() {
        assertEquals(0xFF000000, BattleSurfaceRenderer.interpolate(0xFF000000, 0x00FFFFFF, 0f));
        assertEquals(0x80808080, BattleSurfaceRenderer.interpolate(0xFF000000, 0x00FFFFFF, .5f));
        assertEquals(0x00FFFFFF, BattleSurfaceRenderer.interpolate(0xFF000000, 0x00FFFFFF, 1f));
    }
}
