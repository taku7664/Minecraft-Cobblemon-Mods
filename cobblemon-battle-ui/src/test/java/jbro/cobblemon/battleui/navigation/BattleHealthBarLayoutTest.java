package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jbro.cobblemon.battleui.extended.ui.shared.BattleHealthBarLayout;
import org.junit.jupiter.api.Test;

class BattleHealthBarLayoutTest {
    @Test
    void shortBarsUseTheSameTwoThirdsRuleAcrossViews() {
        assertEquals(0, BattleHealthBarLayout.shortWidth(0));
        assertEquals(32, BattleHealthBarLayout.shortWidth(48));
        assertEquals(45, BattleHealthBarLayout.shortWidth(67));
        assertEquals(69, BattleHealthBarLayout.shortWidth(104));
    }
}
