package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GridMenuNavigatorTest {
    @Test
    void movesThroughTwoColumnPartyGridAndSkipsUnavailablePokemon() {
        GridMenuNavigator navigator = new GridMenuNavigator(
                List.of(true, false, true, true, false, true),
                2,
                0
        );

        assertEquals(2, navigator.move(0, 1));
        assertEquals(3, navigator.move(1, 0));
        assertEquals(5, navigator.move(0, 1));
    }

    @Test
    void entersAtFirstAvailableItemWhenNothingHasFocus() {
        GridMenuNavigator navigator = new GridMenuNavigator(
                List.of(false, true, true, true),
                2,
                -1
        );

        assertEquals(1, navigator.move(0, 1));
    }

    @Test
    void validatesRememberedSelectionAgainstTheCurrentListSize() {
        assertTrue(GridMenuNavigator.isIndexInBounds(0, 1));
        assertFalse(GridMenuNavigator.isIndexInBounds(-1, 1));
        assertFalse(GridMenuNavigator.isIndexInBounds(1, 1));
        assertFalse(GridMenuNavigator.isIndexInBounds(0, 0));
    }
}
