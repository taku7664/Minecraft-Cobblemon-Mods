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
    void partyListKeepsOnlyExistingPokemonInOneColumn() {
        assertEquals(new UiRect(13, 82, 274, 150), BattleScreenGeometry.switchPanel(427, 240));
        assertEquals(List.of(
                new UiRect(13, 99, 120, 20),
                new UiRect(13, 121, 120, 20),
                new UiRect(13, 143, 120, 20),
                new UiRect(13, 165, 120, 20),
                new UiRect(13, 187, 120, 20),
                new UiRect(13, 209, 120, 20)
        ), BattleScreenGeometry.switchTiles(427, 240, 6));
        assertEquals(List.of(
                new UiRect(294, 99, 120, 20),
                new UiRect(294, 121, 120, 20),
                new UiRect(294, 143, 120, 20)
        ), BattleScreenGeometry.switchOpponentTiles(427, 240, 3));
        for (int i = 0; i < 3; i++) {
            UiRect ally = BattleScreenGeometry.switchTiles(427, 240, 3).get(i);
            UiRect opponent = BattleScreenGeometry.switchOpponentTiles(427, 240, 3).get(i);
            assertEquals(427, ally.x() + ally.width() + opponent.x());
            assertEquals(ally.y(), opponent.y());
        }
        assertTrue(BattleScreenGeometry.switchOpponentTiles(320, 240, 3).isEmpty());
        assertEquals(3, BattleScreenGeometry.switchTiles(427, 240, 3).size());
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

    @Test
    void compactHudRowsCancelNativeHorizontalStaggerWithoutChangingVerticalOrder() {
        assertEquals(4, BattleScreenGeometry.compactHudSlotIndent(10f, 2, 1));
        assertEquals(0, BattleScreenGeometry.compactHudSlotIndent(40f, 2, 1));
        assertEquals(8, BattleScreenGeometry.compactHudSlotIndent(10f, 3, 1));
        assertEquals(4, BattleScreenGeometry.compactHudSlotIndent(40f, 3, 1));
        assertEquals(0, BattleScreenGeometry.compactHudSlotIndent(70f, 3, 1));
        assertEquals(0, BattleScreenGeometry.compactHudSlotIndent(20f, 2, 2));
        assertEquals(0, BattleScreenGeometry.compactHudSlotIndent(10f, 1, 1));
    }

    @Test
    void tallerCompactHudKeepsThreePixelRowGapsWithoutShiftingMultiActorTiles() {
        assertEquals(0, BattleScreenGeometry.compactHudVerticalOffset(10f, 3, 1));
        assertEquals(3, BattleScreenGeometry.compactHudVerticalOffset(40f, 3, 1));
        assertEquals(6, BattleScreenGeometry.compactHudVerticalOffset(70f, 3, 1));
        assertEquals(0, BattleScreenGeometry.compactHudVerticalOffset(20f, 2, 2));
        assertEquals(0, BattleScreenGeometry.compactHudVerticalOffset(10.4f, 3, 1));
    }

    @Test
    void edgeHudUsesTheSameTwentyEightPixelRowsAsItsDraft() {
        assertEquals(0, BattleScreenGeometry.compactHudRowCompression(10f, 3, 1));
        assertEquals(2, BattleScreenGeometry.compactHudRowCompression(40f, 3, 1));
        assertEquals(4, BattleScreenGeometry.compactHudRowCompression(70f, 3, 1));
        assertEquals(0, BattleScreenGeometry.compactHudRowCompression(20f, 2, 2));
        assertEquals(0, BattleScreenGeometry.compactHudRowCompression(10.4f, 3, 1));
    }

    @Test
    void targetDraftFitsBetweenCompactHudAndHotbar() {
        assertEquals(new UiRect(121, 110, 184, 74), BattleScreenGeometry.targetPanel(427, 240, 2));
        assertEquals(new UiRect(127, 132, 83, 22), BattleScreenGeometry.targetTile(427, 240, 2, 0, 0));
        assertEquals(new UiRect(216, 156, 83, 22), BattleScreenGeometry.targetTile(427, 240, 2, 1, 1));
        assertEquals(new UiRect(269, 113, 30, 13), BattleScreenGeometry.targetBack(427, 240, 2));

        assertEquals(new UiRect(121, 126, 184, 84), BattleScreenGeometry.targetPanel(427, 240, 3));
        assertEquals(new UiRect(127, 148, 83, 19), BattleScreenGeometry.targetTile(427, 240, 3, 0, 0));
        assertEquals(new UiRect(216, 188, 83, 19), BattleScreenGeometry.targetTile(427, 240, 3, 1, 2));
        assertTrue(BattleScreenGeometry.targetPanel(427, 240, 3).y() +
                BattleScreenGeometry.targetPanel(427, 240, 3).height() < 217);
    }

    @Test
    void targetCardsPreserveNativeSideAndFieldPositionOrdering() {
        assertEquals(new UiRect(127, 148, 83, 19),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 0, true));
        assertEquals(new UiRect(127, 188, 83, 19),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 2, true));
        assertEquals(new UiRect(216, 188, 83, 19),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 3, false));
        assertEquals(new UiRect(216, 148, 83, 19),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 5, false));
    }
}
