package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LeagueHomeLayoutTest {
    @ParameterizedTest
    @CsvSource("320,240", "426,240", "640,360")
    fun `all supported viewport layouts stay inside the shell without panel overlap`(width: Int, height: Int) {
        val layout = LeagueHomeLayout.calculate(width, height)

        assertTrue(layout.shell.contains(layout.header))
        assertTrue(layout.shell.contains(layout.badges))
        assertTrue(layout.shell.contains(layout.challenge))
        assertTrue(layout.shell.contains(layout.footer))
        assertTrue(layout.footer.contains(layout.actionButton))
        assertTrue(layout.challenge.contains(layout.trainerViewport))
        assertFalse(layout.badges.overlaps(layout.challenge))
        assertFalse(layout.header.overlaps(layout.badges))
        assertFalse(layout.header.overlaps(layout.challenge))
        assertFalse(layout.footer.overlaps(layout.badges))
        assertFalse(layout.footer.overlaps(layout.challenge))
        assertTrue(layout.badges.width >= 120)
        assertTrue(layout.challenge.width >= 120)
        assertTrue(layout.trainerViewport.width >= 24)
        assertTrue(layout.trainerViewport.height >= 24)
    }

    @ParameterizedTest
    @CsvSource("320,240,true", "426,240,false", "640,360,false")
    fun `only the narrow viewport stacks content`(width: Int, height: Int, expected: Boolean) {
        assertTrue(LeagueHomeLayout.calculate(width, height).stacked == expected)
    }
}
