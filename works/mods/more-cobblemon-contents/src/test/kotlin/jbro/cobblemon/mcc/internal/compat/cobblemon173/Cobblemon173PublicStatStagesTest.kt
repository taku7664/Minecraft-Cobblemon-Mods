package jbro.cobblemon.mcc.internal.compat.cobblemon173

import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class Cobblemon173PublicStatStagesTest {
    private val giratina = UUID.randomUUID()
    private val gholdengo = UUID.randomUUID()
    private val garchomp = UUID.randomUUID()
    private val names = mapOf("p1a: Giratina" to giratina, "p2a: Gholdengo" to gholdengo, "p1a: Garchomp" to garchomp)

    private fun Cobblemon173PublicStatStages.line(raw: String, batonPass: Boolean = false) {
        val parts = raw.removePrefix("|").split('|')
        val args = parts.drop(1)
        observe(parts[0], pokemon = { names[args.getOrNull(it)] }, argument = { args.getOrNull(it) }, batonPass = batonPass)
    }

    @Test
    fun `boosts and drops from the log accumulate and clamp`() {
        val stages = Cobblemon173PublicStatStages()
        stages.line("|switch|p1a: Giratina|Giratina-Origin, L95|100/100")
        stages.line("|switch|p2a: Gholdengo|Gholdengo|100/100")
        assertEquals(emptyMap<String, Int>(), stages.of(giratina))
        stages.line("|-unboost|p1a: Giratina|spa|2")
        stages.line("|-unboost|p1a: Giratina|spa|2")
        stages.line("|-boost|p2a: Gholdengo|spa|2")
        stages.line("|-boost|p2a: Gholdengo|spa|2")
        stages.line("|-boost|p2a: Gholdengo|spa|2")
        stages.line("|-boost|p2a: Gholdengo|spa|2")
        assertEquals(mapOf("special_attack" to -4), stages.of(giratina))
        assertEquals(mapOf("special_attack" to 6), stages.of(gholdengo))
    }

    @Test
    fun `switching out resets stages and Baton Pass carries them`() {
        val stages = Cobblemon173PublicStatStages()
        stages.line("|switch|p1a: Giratina|Giratina-Origin, L95|100/100")
        stages.line("|-unboost|p1a: Giratina|spa|2")
        stages.line("|switch|p1a: Garchomp|Garchomp, L100|100/100")
        assertEquals(emptyMap<String, Int>(), stages.of(garchomp))
        assertNull(stages.of(giratina))
        stages.line("|-boost|p1a: Garchomp|atk|2")
        stages.line("|switch|p1a: Giratina|Giratina-Origin, L95|100/100", batonPass = true)
        assertEquals(mapOf("attack" to 2), stages.of(giratina))
    }

    @Test
    fun `clearing, setting and inverting follow Haze, Belly Drum and Topsy-Turvy`() {
        val stages = Cobblemon173PublicStatStages()
        stages.line("|switch|p1a: Giratina|Giratina-Origin, L95|100/100")
        stages.line("|switch|p2a: Gholdengo|Gholdengo|100/100")
        stages.line("|-boost|p2a: Gholdengo|spa|2")
        stages.line("|-unboost|p2a: Gholdengo|spe|1")
        stages.line("|-invertboost|p2a: Gholdengo")
        assertEquals(mapOf("special_attack" to -2, "speed" to 1), stages.of(gholdengo))
        stages.line("|-clearnegativeboost|p2a: Gholdengo")
        assertEquals(mapOf("speed" to 1), stages.of(gholdengo))
        stages.line("|-setboost|p1a: Giratina|atk|6")
        stages.line("|-clearallboost")
        assertEquals(emptyMap<String, Int>(), stages.of(giratina))
        assertEquals(emptyMap<String, Int>(), stages.of(gholdengo))
    }
}
