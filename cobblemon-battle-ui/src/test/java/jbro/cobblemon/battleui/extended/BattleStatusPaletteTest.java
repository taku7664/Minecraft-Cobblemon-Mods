package jbro.cobblemon.battleui.extended;

import jbro.cobblemon.battleui.extended.ui.shared.BattleStatusPalette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleStatusPaletteTest {
    @Test void matchesCobblemon181BattleStatusTexturePrimaryColors() {
        assertEquals(0xFFE57034, BattleStatusPalette.background("brn"));
        assertEquals(0xFFBA5CD8, BattleStatusPalette.background("psn"));
        assertEquals(0xFFBA5CD8, BattleStatusPalette.background("tox"));
        assertEquals(0xFFDAA52F, BattleStatusPalette.background("par"));
        assertEquals(0xFF8D8AA4, BattleStatusPalette.background("slp"));
        assertEquals(0xFF5AA4F5, BattleStatusPalette.background("frz"));
    }
}
