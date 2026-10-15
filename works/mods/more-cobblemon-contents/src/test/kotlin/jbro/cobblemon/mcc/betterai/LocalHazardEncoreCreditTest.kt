package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalHazardEncoreCredit
import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalHazardEncoreCreditTest {
    @Test
    fun `encore on a target already locked gets no credit`() {
        // Unlocked, the rule still reads it (here as avoided: the target has used no move to lock into).
        assertTrue("encore" in adjustments(locked = null))
        // Locked, the move fails; the ranking reads it idle and this rule must not price it back up (Milo, 2026-10-16).
        assertFalse("encore" in adjustments(locked = "cobblemon:protect"))
    }

    private fun adjustments(locked: String?): Map<String, Double> {
        fun pokemon(side: BattleSide, constraints: BattlePokemonActionConstraintView) = BattlePokemonStateView(
            battlePokemonId = UUID.nameUUIDFromBytes("$side".toByteArray()), side = side, activeSlot = 0,
            speciesId = "cobblemon:whimsicott", formId = null, level = 50, hpFraction = 1.0, statusId = null,
            statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null,
            fainted = false, actionConstraints = constraints, knownVolatileEffectIds = emptySet())
        val target = locked?.let { BattlePokemonActionConstraintView(encoreMoveId = it) } ?: BattlePokemonActionConstraintView.empty()
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 3,
            listOf(pokemon(BattleSide.ALLY, BattlePokemonActionConstraintView.empty()), pokemon(BattleSide.OPPONENT, target)),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val encore = BattleActionCandidate("encore", BattleActionKind.USE_MOVE, 0, 0, "cobblemon:encore",
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 5,
                BattleMoveTargetPattern.SELECTED))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(encore), Long.MAX_VALUE)
        return LocalHazardEncoreCredit.adjustments(context.candidates, context, MatchupScores(emptyMap(), emptyMap(), true))
    }
}
