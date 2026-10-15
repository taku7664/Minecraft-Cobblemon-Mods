package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** A singles Protect is worth the turn it buys (2026-10-16), not the flat status value. */
class LocalSinglesProtectReturnTest {
    @Test
    fun `nothing to gain from the turn is worth nothing`() {
        assertEquals(0.0, protect(), 1e-6)
    }

    @Test
    fun `a turn of the opponent's poison is what it gains`() {
        // An eighth of the opponent's HP (12.5 on the board), not the flat 20.
        val poisoned = protect(foe = { it.copyState(statusId = "psn") })
        assertTrue(poisoned > 5.0 && poisoned < 20.0, "$poisoned")
        // Its own poison makes the turn a loss.
        assertTrue(protect(user = { it.copyState(statusId = "psn") }) < 0.0)
    }

    @Test
    fun `a turn off the opponent's screen is a gain, off its own a loss`() {
        val theirs = protect(field = mapOf(BattleSide.OPPONENT to listOf(BattleTimedEffectView("reflect", 3))))
        val ours = protect(field = mapOf(BattleSide.ALLY to listOf(BattleTimedEffectView("reflect", 3))))
        assertEquals(15.0 / 3.0, theirs, 1e-6)
        assertEquals(-15.0 / 3.0, ours, 1e-6)
    }

    private fun protect(
        user: (BattlePokemonStateView) -> BattlePokemonStateView = { it },
        foe: (BattlePokemonStateView) -> BattlePokemonStateView = { it },
        field: Map<BattleSide, List<BattleTimedEffectView>> = emptyMap(),
    ): Double {
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 3,
            listOf(user(mon(BattleSide.ALLY)), foe(mon(BattleSide.OPPONENT))),
            BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { field[it].orEmpty() }),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val candidate = BattleActionCandidate("protect", BattleActionKind.USE_MOVE, 0, 0, "cobblemon:protect",
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 4, 10,
                BattleMoveTargetPattern.SELF, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(
                    BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.USER, probability = 1.0, valueId = "protect"),
                    BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER, probability = 1.0)), false,
                    mechanicFlags = setOf("stalling_move", "stall_counter_advance"))))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(candidate), Long.MAX_VALUE)
        return LocalNonDamagingMoveEvaluator.pressure(candidate, context, 1.0)
    }

    private fun mon(side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = UUID.nameUUIDFromBytes("$side".toByteArray()), side = side, activeSlot = 0, speciesId = "cobblemon:snorlax",
        formId = null, level = 50, hpFraction = 0.5, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = "thickfat", knownHeldItemId = "", fainted = false,
        knownTypeIds = setOf("normal"), knownVolatileEffectIds = emptySet())
}
