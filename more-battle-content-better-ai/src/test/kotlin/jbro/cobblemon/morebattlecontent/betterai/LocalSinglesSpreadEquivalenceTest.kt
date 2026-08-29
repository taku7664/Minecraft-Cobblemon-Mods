package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Teaching the simulation about spread moves must not have changed what singles decides.
 *
 * The fixture used to drop Showdown's target field and call every move single-target, so a simulated
 * Earthquake hit one Pokemon even in doubles. Reading the field is a fix for doubles, but the same
 * fixture feeds every singles self-play measurement in this plan - including the numbers behind every
 * rejection recorded in it - so what it does to singles has to be known rather than assumed.
 *
 * Gen 9 charges the three quarters for actually landing on more than one target. With one opponent on
 * the field a spread move is an ordinary attack, so the facts must come out identical whichever
 * target pattern the move is declared with. If they do, the singles node count moving is a different
 * battle being played - the move's identifier changed, and identifiers break ties - rather than a
 * different judgement being made.
 */
class LocalSinglesSpreadEquivalenceTest {
    @Test
    fun `a spread move is priced exactly like a single-target one against a lone opponent`() {
        val single = facts(BattleMoveTargetPattern.SELECTED_OPPONENT, explicitTarget = true)
        val spread = facts(BattleMoveTargetPattern.ALL_OPPONENTS, explicitTarget = false)
        assertEquals(
            requireNotNull(single?.standardDamageFractionRange).maximum,
            requireNotNull(spread?.standardDamageFractionRange).maximum,
            1.0e-9,
            "One opponent means no spread reduction, so the damage has to match exactly.",
        )
        assertEquals(
            single?.standardKnockoutAssessment, spread?.standardKnockoutAssessment,
            "And so does the knockout assessment built from it.",
        )
        assertEquals(
            single?.typeChartMultiplier, spread?.typeChartMultiplier,
            "The type chart never depended on how many slots the move reaches.",
        )
    }

    @Test
    fun `an adjacent spread move has no ally to catch in singles`() {
        val single = facts(BattleMoveTargetPattern.SELECTED_OPPONENT, explicitTarget = true)
        val adjacent = facts(BattleMoveTargetPattern.ALL_ADJACENT, explicitTarget = false)
        assertEquals(
            requireNotNull(single?.standardDamageFractionRange).maximum,
            requireNotNull(adjacent?.standardDamageFractionRange).maximum,
            1.0e-9,
            "ALL_ADJACENT also reaches one's own partner, and in singles there is not one.",
        )
    }

    @Test
    fun `the spread facts stay empty when only one slot is struck`() {
        val spread = facts(BattleMoveTargetPattern.ALL_OPPONENTS, explicitTarget = false)
        assertEquals(
            emptyList<BattleSpreadTargetFactsView>(), spread?.spreadTargets,
            "Per-slot facts describe a move that hits several slots. One slot is an ordinary move, " +
                "and a reader that saw entries here would price the turn twice.",
        )
    }

    private fun facts(
        pattern: BattleMoveTargetPattern,
        explicitTarget: Boolean,
    ): BattleCandidateFactsView? {
        val ally = mon(BattleSide.ALLY)
        val opponent = mon(BattleSide.OPPONENT)
        val move = BattleActionCandidate(
            actionId = "quake", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:earthquake",
            targets = if (explicitTarget) listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList(),
            moveDetails = BattleMoveCandidateView(
                typeId = "ground", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 100.0,
                accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = pattern,
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 3 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicBattleTacticalCalculator.calculate(context).candidates.single().facts
    }

    private fun mon(side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe_${side.name.lowercase()}", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 140, 100, 100, 100, 100)
        } else {
            publicExactStats(200, 100, 100, 100, 100, 90)
        },
    )
}
