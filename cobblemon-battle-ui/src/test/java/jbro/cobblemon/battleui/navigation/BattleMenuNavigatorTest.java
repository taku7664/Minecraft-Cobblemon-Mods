package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BattleMenuNavigatorTest {
    @Test
    void skipsDisabledActionsAndWraps() {
        BattleMenuNavigator navigator = new BattleMenuNavigator(List.of(true, false, true, true), 0);

        assertEquals(2, navigator.move(1));
        assertEquals(3, navigator.move(1));
        assertEquals(0, navigator.move(1));
        assertEquals(3, navigator.move(-1));
    }

    @Test
    void keepsNoSelectionWhenEveryActionIsDisabled() {
        BattleMenuNavigator navigator = new BattleMenuNavigator(List.of(false, false), -1);

        assertEquals(-1, navigator.move(1));
    }

    @Test
    void movesThroughVerticalMoveListAndSkipsUnselectableMoves() {
        BattleMenuNavigator navigator = new BattleMenuNavigator(
                List.of(true, false, true, true),
                0
        );

        assertEquals(2, navigator.move(1));
        assertEquals(3, navigator.move(1));
        assertEquals(0, navigator.move(1));
        assertEquals(3, navigator.move(-1));
    }
}
