package jbro.cobblemon.mcc.api.battle

import jbro.cobblemon.mcc.internal.ai.BattleCombatStatKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamPreviewSelectionTest {
    private val fireTeam = BattleOpponentTeamPreviewView(3, (0 until 6).map { slot ->
        BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:probe$slot", null, 50, setOf("fire"), stats(100))
    })

    private val species = mapOf(
        "cobblemon:waterprobe" to setOf("water"),
        "cobblemon:grassprobe" to setOf("grass"),
    )

    private val moves = mapOf(
        "surf" to move("water", 90.0),
        "watergun" to move("water", 40.0),
        "gigadrain" to move("grass", 75.0),
    )

    private fun scorer(tier: BattleTrainerTier) = TeamPreviewSelection.scorer(
        tier, fireTeam,
        { speciesId, _, _ -> species[speciesId]?.let { TeamPreviewSelection.Facts(it, stats(100)) } },
        moves::get,
    )

    private fun candidate(species: String, vararg moves: String) =
        TeamPreviewSelection.Candidate("cobblemon:$species", null, 50, moves.toList())

    @Test
    fun `an Introductory trainer does not read the preview`() {
        assertNull(scorer(BattleTrainerTier.INTRODUCTORY))
    }

    @Test
    fun `every tier that reads the preview brings water against a fire team`() {
        listOf(BattleTrainerTier.STANDARD, BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS).forEach { tier ->
            val scorer = requireNotNull(scorer(tier))
            val water = requireNotNull(scorer.score(candidate("waterprobe", "surf")))
            val grass = requireNotNull(scorer.score(candidate("grassprobe", "gigadrain")))
            assertTrue(water > grass, "$tier: water $water, grass $grass")
        }
    }

    @Test
    fun `a Standard trainer reads types alone and an Advanced trainer move power too`() {
        fun gap(tier: BattleTrainerTier): Double {
            val scorer = requireNotNull(scorer(tier))
            return requireNotNull(scorer.score(candidate("waterprobe", "surf"))) -
                requireNotNull(scorer.score(candidate("waterprobe", "watergun")))
        }
        assertEquals(0.0, gap(BattleTrainerTier.STANDARD), 1e-9)
        assertTrue(gap(BattleTrainerTier.ADVANCED) > 0.0)
    }

    @Test
    fun `higher tiers follow the scores more sharply and unknown species are not scored`() {
        val temperatures = listOf(BattleTrainerTier.STANDARD, BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS)
            .map { requireNotNull(scorer(it)).temperature }
        assertEquals(temperatures.sortedDescending(), temperatures)
        assertTrue(temperatures.distinct().size == 3, "$temperatures")
        assertNull(requireNotNull(scorer(BattleTrainerTier.BOSS)).score(candidate("unknownprobe", "surf")))
    }

    private fun move(type: String, power: Double) =
        BattleMoveCandidateView(type, BattleMoveDamageCategory.SPECIAL, power, 1.0, 0, 10)

    /** Public species ranges around [value], as a preview carries them. */
    private fun stats(value: Int) = BattleCombatStatRangesView(
        maxHp = BattleIntegerRange(value + 40, value + 60), attack = BattleIntegerRange(value - 10, value + 10),
        defence = BattleIntegerRange(value - 10, value + 10), specialAttack = BattleIntegerRange(value - 10, value + 10),
        specialDefence = BattleIntegerRange(value - 10, value + 10), speed = BattleIntegerRange(value - 10, value + 10),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )
}
