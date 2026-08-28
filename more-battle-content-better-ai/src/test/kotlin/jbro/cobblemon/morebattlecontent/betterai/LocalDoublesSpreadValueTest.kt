package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The one thing that makes a spread move worth clicking has to be visible.
 *
 * Doubles had no measurement of any kind until the harness learned to play it, and running is not
 * the same as judging well. This asks the narrowest question that separates the two: in a doubles
 * position, does hitting both opponents beat hitting one?
 *
 * It is a real risk rather than a hypothetical. Gen 9 charges a spread move 0.75x for the privilege,
 * so a projection that prices only the primary target reports a spread move as a *worse* attack than
 * the single-target one it is being compared against - which is exactly backwards, and would have
 * been invisible while nothing played doubles.
 *
 * The singles case is asserted alongside it, and it is the more easily got wrong of the two: Gen 9
 * charges the three quarters for *actually landing* on more than one target, not for being the kind
 * of move that could. Against a lone opponent a spread move is an ordinary attack.
 */
class LocalDoublesSpreadValueTest {
    @Test
    fun `hitting both opponents beats hitting one`() {
        val breakdown = LocalDecisionInstrumentation.inspect(context(BattleFormat.DOUBLE))
        println(breakdown.format("doubles-spread"))
        assertEquals(
            "spread", breakdown.chosenByRanking?.actionId,
            "Two targets at three quarters each beats one at full: " +
                breakdown.candidates.joinToString(" | ") { "${it.actionId}=${it.comparisonValue}" },
        )
    }

    @Test
    fun `the reduction is not charged when the move only reaches one target`() {
        val breakdown = LocalDecisionInstrumentation.inspect(context(BattleFormat.SINGLE))
        println(breakdown.format("singles-spread"))
        val spread = requireNotNull(breakdown.candidates.single { it.actionId == "spread" })
        val single = requireNotNull(breakdown.candidates.single { it.actionId == "single" })
        // Gen 9 charges the three quarters for actually landing on more than one target, not for
        // being the kind of move that could. Against a lone opponent the two are the same attack, so
        // the honest assertion is that they tie - asserting a winner here would have passed on
        // whichever way the tie happened to break and hidden a wrong reduction underneath.
        assertEquals(
            single.comparisonValue, spread.comparisonValue, 1.0e-9,
            "With one opponent on the field the spread move is the same attack: " +
                breakdown.candidates.joinToString(" | ") { "${it.actionId}=${it.comparisonValue}" },
        )
    }

    @Test
    fun `the second slot is published as its own set of facts`() {
        val calculated = PublicBattleTacticalCalculator.calculate(context(BattleFormat.DOUBLE))
        val spread = calculated.candidates.single { it.actionId == "spread" }
        val targets = requireNotNull(spread.facts).spreadTargets
        assertEquals(2, targets.size, "Both opposing slots are struck, so both are described.")
        assertTrue(
            targets.all { it.standardDamageFractionRange != null },
            "A slot named without a damage figure is a slot the ranking cannot weigh.",
        )
        assertEquals(
            listOf(0, 1), targets.map { it.slot },
            "In active-slot order, with the primary target repeated first.",
        )
    }

    private fun context(format: BattleFormat): BattleDecisionContext {
        val doubles = format == BattleFormat.DOUBLE
        val allies = listOf(mon(BattleSide.ALLY, 0)) + if (doubles) listOf(mon(BattleSide.ALLY, 1)) else emptyList()
        val opponents = listOf(mon(BattleSide.OPPONENT, 0)) +
            if (doubles) listOf(mon(BattleSide.OPPONENT, 1)) else emptyList()
        val spread = attack("spread", BattleMoveTargetPattern.ALL_OPPONENTS, targets = emptyList())
        val single = attack(
            "single",
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        )
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = format, turn = 2,
                pokemon = allies + opponents, field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { if (doubles) 4 else 3 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(spread, single), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    /** Identical moves in every respect but how many slots they reach. */
    private fun attack(
        id: String,
        pattern: BattleMoveTargetPattern,
        targets: List<BattleTargetSlot>,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = targets,
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 90.0,
            accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = pattern,
        ),
    )

    private fun mon(side: BattleSide, slot: Int) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe_${side.name.lowercase()}_$slot", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 140, 100, 100, 100, 100)
        } else {
            publicExactStats(200, 100, 100, 100, 100, 90)
        },
    )
}
