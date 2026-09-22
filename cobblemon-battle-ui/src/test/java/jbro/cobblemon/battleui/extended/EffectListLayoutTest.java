package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jbro.cobblemon.battleui.extended.ui.champions.EffectListLayout;
import org.junit.jupiter.api.Test;

final class EffectListLayoutTest {
    @Test
    void showsEveryEffectWhenThePanelHasRoom() {
        var window = EffectListLayout.INSTANCE.window(8);

        assertEquals(8, window.getVisibleEffectCount());
        assertEquals(0, window.getHiddenEffectCount());
    }

    @Test
    void reservesTheLastRowForAnOverflowSummary() {
        var nineEffects = EffectListLayout.INSTANCE.window(9);
        var twelveEffects = EffectListLayout.INSTANCE.window(12);

        assertEquals(7, nineEffects.getVisibleEffectCount());
        assertEquals(2, nineEffects.getHiddenEffectCount());
        assertEquals(7, twelveEffects.getVisibleEffectCount());
        assertEquals(5, twelveEffects.getHiddenEffectCount());
    }
}
