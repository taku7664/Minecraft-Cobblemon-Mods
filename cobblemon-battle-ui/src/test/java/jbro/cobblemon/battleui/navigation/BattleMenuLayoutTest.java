package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BattleMenuLayoutTest {
    @Test
    void edgeControlsKeepTheirRightEdgeOnTheViewportWhileFocusExtendsLeft() {
        List<UiRect> commands = BattleMenuLayout.vertical(427, 240, 90, 26, 4, 0, 10, 3);

        assertEquals(427, commands.getFirst().x() + commands.getFirst().width());
        assertEquals(330, commands.getFirst().x() - BattleScreenGeometry.FOCUS_PROTRUSION);
    }

    @Test
    void anchorsVerticalButtonsToBottomRightWithoutMovingTheirSpacing() {
        List<UiRect> buttons = BattleMenuLayout.vertical(320, 180, 88, 20, 4, 12, 10, 4);

        assertEquals(List.of(
                new UiRect(220, 78, 88, 20),
                new UiRect(220, 102, 88, 20),
                new UiRect(220, 126, 88, 20),
                new UiRect(220, 150, 88, 20)
        ), buttons);
    }

    @Test
    void placesFourCobblemonMoveTilesInTheSameBottomRightColumn() {
        List<UiRect> moves = BattleMenuLayout.vertical(320, 180, 92, 24, 4, 12, 10, 4);

        assertEquals(List.of(
                new UiRect(216, 62, 92, 24),
                new UiRect(216, 90, 92, 24),
                new UiRect(216, 118, 92, 24),
                new UiRect(216, 146, 92, 24)
        ), moves);
    }

    @Test
    void keepsTheLeadingButtonCornerVisibleInASmallViewport() {
        List<UiRect> buttons = BattleMenuLayout.vertical(80, 60, 88, 20, 4, 12, 10, 4);

        assertEquals(new UiRect(0, 0, 68, 20), buttons.getFirst());
    }
}
