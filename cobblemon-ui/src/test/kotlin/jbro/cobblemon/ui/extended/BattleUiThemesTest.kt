package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleControlShape
import jbro.cobblemon.ui.extended.ui.shared.BattleDialogueStyle
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.ui.extended.ui.shared.BattleUiThemes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleUiThemesTest {
    @AfterEach
    fun restore() {
        BattleUiThemes.select(BattleUiThemes.CHAMPIONS.id)
    }

    @Test
    fun `themes have distinct ids and Champions is the default`() {
        assertEquals(BattleUiThemes.all.size, BattleUiThemes.all.map { it.id }.toSet().size)
        assertSame(BattleUiThemes.CHAMPIONS, BattleUiThemes.current)
    }

    @Test
    fun `selecting a theme restyles every reader at once`() {
        val championsShell = BattleUiTheme.shell
        assertTrue(BattleUiThemes.select("galar"))
        assertSame(BattleUiThemes.GALAR, BattleUiTheme.palette)
        assertNotEquals(championsShell, BattleUiTheme.shell)
        assertEquals(BattleUiThemes.GALAR.text, BattleUiTheme.TEXT)
        assertEquals(BattleUiThemes.GALAR.ally, BattleUiTheme.CYAN)
    }

    @Test
    fun `an unknown id keeps the theme in use`() {
        BattleUiThemes.select("galar")
        assertFalse(BattleUiThemes.select("no-such-theme"))
        assertSame(BattleUiThemes.GALAR, BattleUiThemes.current)
    }

    @Test
    fun `Sword and Shield styles the same screens differently`() {
        val galar = BattleUiThemes.GALAR
        assertTrue(galar.light)
        assertEquals(BattleControlShape.PILL, galar.controlShape)
        assertEquals(BattleDialogueStyle.DARK_BAND, galar.dialogue)
        assertNotNull(galar.focusFill)
        // Every command keeps a color of its own.
        assertEquals(4, setOf(galar.fightAccent, galar.switchAccent, galar.bagAccent, galar.runAccent).size)
    }

    @Test
    fun `readable deepens colors only on a light theme`() {
        val pastel = 0xFFFFD250.toInt()
        assertEquals(pastel, BattleUiTheme.readable(pastel))
        BattleUiThemes.select("galar")
        val deepened = BattleUiTheme.readable(pastel)
        assertEquals(pastel ushr 24, deepened ushr 24)
        assertTrue((deepened shr 16 and 255) < (pastel shr 16 and 255))
    }
}
