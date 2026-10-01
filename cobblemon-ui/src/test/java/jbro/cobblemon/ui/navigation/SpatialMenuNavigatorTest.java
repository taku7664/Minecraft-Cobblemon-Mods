package jbro.cobblemon.ui.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpatialMenuNavigatorTest {
    private final List<UiRect> sideGrouped = List.of(
            new UiRect(127, 148, 83, 19), new UiRect(127, 168, 83, 19),
            new UiRect(127, 188, 83, 19), new UiRect(216, 188, 83, 19),
            new UiRect(216, 168, 83, 19), new UiRect(216, 148, 83, 19));

    @Test void directionsFollowVisiblePositionsNotTheBackingListOrder() {
        List<Boolean> enabled = List.of(true, true, true, true, true, true);
        assertEquals(0, SpatialMenuNavigator.move(sideGrouped, enabled, -1, 0, 1));
        assertEquals(5, SpatialMenuNavigator.move(sideGrouped, enabled, 0, 1, 0));
        assertEquals(4, SpatialMenuNavigator.move(sideGrouped, enabled, 5, 0, 1));
        assertEquals(1, SpatialMenuNavigator.move(sideGrouped, enabled, 4, -1, 0));
        assertEquals(1, SpatialMenuNavigator.move(sideGrouped, enabled, 1, -1, 0));
    }

    @Test void skipsUnavailableCardsWithoutSelectingAnInvisibleSlot() {
        List<Boolean> enabled = List.of(true, false, true, true, false, true);
        assertEquals(3, SpatialMenuNavigator.move(sideGrouped, enabled, 5, 0, 1));
        assertEquals(2, SpatialMenuNavigator.move(sideGrouped, enabled, 3, -1, 0));
    }
}
