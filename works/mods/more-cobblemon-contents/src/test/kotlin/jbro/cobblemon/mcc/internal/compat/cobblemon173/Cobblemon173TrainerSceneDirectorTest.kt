package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.api.presentation.TrainerScenes.Moment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Cobblemon173TrainerSceneDirectorTest {
    private fun moment(line: String) = Cobblemon173TrainerSceneDirector.moment(line, "p2")

    @Test
    fun `the trainer's Dynamax and Mega Evolution are read off the log`() {
        assertEquals(Moment.DYNAMAX, moment("|-start|p2a: Drednaw|Dynamax"))
        assertEquals(Moment.DYNAMAX, moment("|-start|p2a: Drednaw|Dynamax|Gmax"))
        assertEquals(Moment.MEGA_EVOLUTION, moment("|-mega|p2a: Garchomp|Garchomp|Garchompite"))
    }

    @Test
    fun `the player's side and other lines say nothing`() {
        assertNull(moment("|-start|p1a: Charizard|Dynamax"))
        assertNull(moment("|-mega|p1a: Lucario|Lucario|Lucarionite"))
        assertNull(moment("|-end|p2a: Drednaw|Dynamax"))
        assertNull(moment("|-start|p2a: Drednaw|confusion"))
        assertNull(moment("|detailschange|p2a: Garchomp|Garchomp-Mega"))
        assertNull(moment("|turn|3"))
    }
}
