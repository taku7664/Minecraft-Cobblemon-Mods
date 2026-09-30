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
                new UiRect(287, 76, 140, 32),
                new UiRect(287, 112, 140, 32),
                new UiRect(287, 148, 140, 32),
                new UiRect(287, 184, 140, 32)
        ), BattleScreenGeometry.moveTiles(427, 240, 4));
    }

    @Test
    void movesClearTheHotbarOnlyWhereTheyWouldOverlapIt() {
        // At 427 wide the focused column reaches x 280, left of the hotbar's right end at 304.
        UiRect narrowBottom = BattleScreenGeometry.moveTiles(427, 240, 4).getLast();
        assertTrue(narrowBottom.y() + narrowBottom.height() <= 240 - 22);
        UiRect wideBottom = BattleScreenGeometry.moveTiles(640, 360, 4).getLast();
        assertEquals(360 - 16, wideBottom.y() + wideBottom.height());
    }

    @Test
    void partyListKeepsOnlyExistingPokemonInOneColumn() {
        assertEquals(new UiRect(13, 70, 401, 150), BattleScreenGeometry.switchPanel(427, 240));
        assertEquals(List.of(
                new UiRect(13, 87, 105, 20),
                new UiRect(13, 109, 105, 20),
                new UiRect(13, 131, 105, 20),
                new UiRect(13, 153, 105, 20),
                new UiRect(13, 175, 105, 20),
                new UiRect(13, 197, 105, 20)
        ), BattleScreenGeometry.switchTiles(427, 240, 6));
        assertEquals(List.of(
                new UiRect(309, 87, 105, 20),
                new UiRect(309, 109, 105, 20),
                new UiRect(309, 131, 105, 20)
        ), BattleScreenGeometry.switchOpponentTiles(427, 240, 3));
        assertEquals(new UiRect(124, 87, 179, 125), BattleScreenGeometry.switchDetails(427, 240));
        assertEquals(new UiRect(269, 70, 34, 17), BattleScreenGeometry.switchBack(427, 240));
        for (int i = 0; i < 3; i++) {
            UiRect ally = BattleScreenGeometry.switchTiles(427, 240, 3).get(i);
            UiRect opponent = BattleScreenGeometry.switchOpponentTiles(427, 240, 3).get(i);
            assertEquals(427, ally.x() + ally.width() + opponent.x());
            assertEquals(ally.y(), opponent.y());
        }
        assertTrue(BattleScreenGeometry.switchOpponentTiles(320, 240, 3).isEmpty());
        assertTrue(BattleScreenGeometry.switchDetails(320, 240).width() >
                BattleScreenGeometry.switchDetails(427, 240).width());
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
        UiRect doubles = BattleScreenGeometry.targetPanel(427, 240, 2);
        assertEquals(248, doubles.width());
        assertEquals(84, doubles.height());
        assertEquals(19, BattleScreenGeometry.targetTile(427, 240, 2, 0, 0).height());
        assertEquals(12, BattleScreenGeometry.targetBack(427, 240, 2).height());

        UiRect triples = BattleScreenGeometry.targetPanel(427, 240, 3);
        assertEquals(310, triples.width());
        assertEquals(84, triples.height());
        assertEquals(19, BattleScreenGeometry.targetTile(427, 240, 3, 1, 2).height());
        assertTrue(BattleScreenGeometry.targetPanel(427, 240, 3).y() +
                BattleScreenGeometry.targetPanel(427, 240, 3).height() < 217);
    }

    @Test
    void targetCardsPreserveNativeSideAndFieldPositionOrdering() {
        assertEquals(BattleScreenGeometry.targetTile(427, 240, 3, 0, 0),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 0, true));
        assertEquals(BattleScreenGeometry.targetTile(427, 240, 3, 0, 2),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 2, true));
        assertEquals(BattleScreenGeometry.targetTile(427, 240, 3, 1, 2),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 3, false));
        assertEquals(BattleScreenGeometry.targetTile(427, 240, 3, 1, 0),
                BattleScreenGeometry.targetTileForIndex(427, 240, 3, 5, false));
    }

    @Test
    void targetButtonsUseTheirAllocatedWidthAsTheirClickArea() {
        UiRect slot = BattleScreenGeometry.targetTile(427, 240, 2, 1, 0);
        UiRect button = BattleScreenGeometry.targetCard(slot);
        assertEquals(slot, button);
        assertTrue(button.contains(button.x() + 1, button.y() + 1));
        assertFalse(button.contains(slot.x() - 1, slot.y() + 1));
        assertEquals(BattleScreenGeometry.targetTile(427, 240, 3, 1, 0).width(),
                BattleScreenGeometry.targetCard(BattleScreenGeometry.targetTile(427, 240, 3, 1, 0)).width());
    }
}
