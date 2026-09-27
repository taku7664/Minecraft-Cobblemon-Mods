package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BattleScreenGeometryTest {
    @Test
    void enlargedMoveColumnKeepsHitboxesOnTheVisualTiles() {
        assertEquals(List.of(
                new UiRect(275, 84, 140, 32),
                new UiRect(275, 120, 140, 32),
                new UiRect(275, 156, 140, 32),
                new UiRect(275, 192, 140, 32)
        ), BattleScreenGeometry.moveTiles(427, 240, 4));
    }

    @Test
    void partyGridMatchesTwoColumnsAndThreeRowsInSmallViewport() {
        assertEquals(new UiRect(83, 82, 260, 150), BattleScreenGeometry.switchPanel(427, 240));
        assertEquals(List.of(
                new UiRect(92, 107, 118, 34),
                new UiRect(216, 107, 118, 34),
                new UiRect(92, 146, 118, 34),
                new UiRect(216, 146, 118, 34),
                new UiRect(92, 185, 118, 34),
                new UiRect(216, 185, 118, 34)
        ), BattleScreenGeometry.switchTiles(427, 240, 6));
        for (UiRect tile : BattleScreenGeometry.switchTiles(427, 240, 6)) {
            assertTrue(BattleScreenGeometry.switchPanel(427, 240).contains(tile.x() + 1, tile.y() + 1));
        }
    }

    @Test
    void forfeitButtonsHaveSeparateVisualAndInteractiveBounds() {
        assertEquals(new UiRect(103, 84, 220, 88), BattleScreenGeometry.forfeitPanel(427, 240));
        assertEquals(new UiRect(139, 134, 68, 24), BattleScreenGeometry.forfeitAccept(427, 240));
        assertEquals(new UiRect(219, 134, 68, 24), BattleScreenGeometry.forfeitCancel(427, 240));
        assertTrue(BattleScreenGeometry.forfeitAccept(427, 240).contains(145, 138));
        assertFalse(BattleScreenGeometry.forfeitAccept(427, 240).contains(225, 138));
    }

    @Test
    void keyboardGridFollowsRenderedColumnsAndSkipsUnavailableSlots() {
        GridMenuNavigator navigator = new GridMenuNavigator(
                List.of(false, true, true, true, true, false), 2, -1
        );
        assertEquals(1, navigator.move(0, 1));
        assertEquals(3, navigator.move(0, 1));
        assertEquals(2, navigator.move(-1, 0));
        assertEquals(4, navigator.move(0, 1));
    }
}
