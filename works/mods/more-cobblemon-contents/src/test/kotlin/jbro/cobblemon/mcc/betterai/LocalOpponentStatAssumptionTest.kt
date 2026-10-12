package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalOpponentStatAssumptionTest {
    @Test
    fun `a revealed opponent named by its Showdown id takes the tier's assumed spread`() {
        // Recorded play: revealed opponents are `showdown:koraidon`, the preview `cobblemon:koraidon`. Boss reads the
        // exact spread, so every stat of every revealed opponent becomes one value instead of the public species range.
        val context = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/togekiss-low-heals-against-koraidon.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .let(AiTestDecisionSnapshot::fromJson).context
        val opponents = context.state.pokemon.filter { it.side == BattleSide.OPPONENT }
        assertTrue(opponents.isNotEmpty() && opponents.all { it.speciesId.startsWith("showdown:") })
        val assumed = LocalOpponentStatAssumption.applyToPublicState(context, BattleTrainerTier.BOSS)
            .state.pokemon.filter { it.side == BattleSide.OPPONENT }
        assertEquals(opponents.size, assumed.size)
        assumed.forEach { pokemon ->
            val stats = requireNotNull(pokemon.combatStats)
            listOf(stats.maxHp, stats.attack, stats.defence, stats.specialAttack, stats.specialDefence, stats.speed).forEach {
                assertEquals(it.minimum, it.maximum, "${pokemon.speciesId} $stats")
            }
        }
    }
}
