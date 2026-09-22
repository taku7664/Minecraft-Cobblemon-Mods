package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TeamPanelLayoutTest {
    @Test
    void horizontalTeamIncludesBackgroundPaddingInItsViewportBounds() {
        TeamPanelLayout layout = TeamPanelLayout.calculate(6, 24, 3, false);

        assertEquals(159, layout.modelWidth());
        assertEquals(24, layout.modelHeight());
        assertEquals(169, layout.panelWidth());
        assertEquals(28, layout.panelHeight());
        assertEquals(156, layout.resolveX(500, 12, 320));
    }

    @Test
    void verticalTeamUsesTheSameGeometryForPositionAndRendering() {
        TeamPanelLayout layout = TeamPanelLayout.calculate(6, 24, 3, true);

        assertEquals(24, layout.modelWidth());
        assertEquals(159, layout.modelHeight());
        assertEquals(34, layout.panelWidth());
        assertEquals(163, layout.panelHeight());
        assertEquals(5, layout.resolveX(-20, 12, 320));
        assertEquals(19, layout.resolveY(500, 40, 180));
    }

    @Test
    void emptyTeamHasNoPanelOrHelpIconArea() {
        TeamPanelLayout layout = TeamPanelLayout.calculate(0, 24, 3, false);

        assertEquals(0, layout.modelWidth());
        assertEquals(0, layout.modelHeight());
        assertEquals(0, layout.panelWidth());
        assertEquals(0, layout.panelHeight());
    }

    @Test
    void dragLayoutRecoversTheSameModelBoundsFromRenderedPanelBounds() {
        TeamPanelLayout layout = TeamPanelLayout.fromPanelSize(169, 28);

        assertEquals(159, layout.modelWidth());
        assertEquals(24, layout.modelHeight());
        assertEquals(156, layout.resolveX(500, 12, 320));
        assertEquals(154, layout.resolveY(500, 40, 180));
    }
}
