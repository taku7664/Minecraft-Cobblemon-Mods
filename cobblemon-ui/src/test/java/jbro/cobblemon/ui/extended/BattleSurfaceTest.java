package jbro.cobblemon.ui.extended;

import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer;
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleSurfaceTest {
    @Test void battleThemeSurfacesHaveNoPerimeterBorder() {
        BattleUiTheme theme = BattleUiTheme.INSTANCE;
        assertEquals(0, theme.getShell().getBorderWidth());
        assertEquals(0, theme.getPanel().getBorderWidth());
        assertEquals(0, theme.getPrimary().getBorderWidth());
        assertEquals(0, theme.getSecondary().getBorderWidth());
        assertEquals(0, theme.getDanger().getBorderWidth());
        assertEquals(0, theme.getCapture().getBorderWidth());
        assertEquals(0, theme.getTranscriptSelf().getBorderWidth());
        assertEquals(0, theme.getTranscriptOpponent().getBorderWidth());
    }

    @Test void selectedCornersCutOnlyTheirOwnEnd() {
        assertEquals(4, BattleSurfaceRenderer.inset(0, 24, 4, true, false));
        assertEquals(3, BattleSurfaceRenderer.inset(1, 24, 4, true, false));
        assertEquals(0, BattleSurfaceRenderer.inset(23, 24, 4, true, false));
        assertEquals(4, BattleSurfaceRenderer.inset(23, 24, 4, false, true));
    }

    @Test void asymmetricCornerCutsKeepOppositeEdgesIndependent() {
        assertEquals(3, BattleSurfaceRenderer.insetAsymmetric(0, 30, 3, 10));
        assertEquals(0, BattleSurfaceRenderer.insetAsymmetric(10, 30, 3, 10));
        assertEquals(5, BattleSurfaceRenderer.insetAsymmetric(24, 30, 3, 10));
        assertEquals(10, BattleSurfaceRenderer.insetAsymmetric(29, 30, 3, 10));
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
