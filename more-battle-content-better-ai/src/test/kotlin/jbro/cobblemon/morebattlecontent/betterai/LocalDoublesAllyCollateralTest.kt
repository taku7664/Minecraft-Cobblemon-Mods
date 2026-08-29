package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Earthquake hits your own side, and the price of that has to be the right size.
 *
 * That it is charged at all was already true. What had never been measured is how much, and the two
 * quantities being compared are built on different scales: the pressure a projected move earns is an
 * HP fraction times a hundred, while the collateral it is charged is raw base power times the type
 * chart. A hundred-power move can therefore be charged more for grazing an ally than it earns for
 * knocking out an opponent, and the symptom would be an AI that simply never presses a spread Ground
 * move in doubles - which reads as caution rather than as a bug.
 *
 * Three positions separate the possibilities. A Flying ally is immune, so nothing should be charged.
 * A grounded ally is hit, so something must be. And an opponent pair that Earthquake beats should
 * still be worth beating.
 */
class LocalDoublesAllyCollateralTest {
    @Test
    fun `an immune ally costs nothing`() {
        val breakdown = LocalDecisionInstrumentation.inspect(context(allyTypes = setOf("flying")))
        println(breakdown.format("collateral-immune-ally"))
        assertEquals(
            "earthquake", breakdown.chosenByRanking?.actionId,
            "A Flying partner cannot be hit by it, so the spread move is simply the better attack: " +
                breakdown.candidates.joinToString(" | ") { "${it.actionId}=${it.comparisonValue}" },
        )
    }

    @Test
    fun `a grounded ally is charged for`() {
        val immune = LocalDecisionInstrumentation.inspect(context(allyTypes = setOf("flying")))
        val grounded = LocalDecisionInstrumentation.inspect(context(allyTypes = setOf("normal")))
        println(grounded.format("collateral-grounded-ally"))
        val immuneQuake = requireNotNull(immune.candidates.single { it.actionId == "earthquake" })
        val groundedQuake = requireNotNull(grounded.candidates.single { it.actionId == "earthquake" })
        assertTrue(
            groundedQuake.comparisonValue < immuneQuake.comparisonValue,
            "Hitting the partner has to cost something: grounded=${groundedQuake.comparisonValue} " +
                "immune=${immuneQuake.comparisonValue}",
        )
    }

    @Test
    fun `the charge does not swamp the attack it is attached to`() {
        val grounded = LocalDecisionInstrumentation.inspect(context(allyTypes = setOf("normal")))
        val quake = requireNotNull(grounded.candidates.single { it.actionId == "earthquake" })
        val single = requireNotNull(grounded.candidates.single { it.actionId == "single" })
        // The comparison that matters is against the alternative, not against zero. Charging raw base
        // power against a value earned in HP fractions would put the spread move below a move that
        // hits one opponent for less total damage, and would do it by a margin no board reading
        // supports.
        println(
            "grounded earthquake=${quake.comparisonValue} single=${single.comparisonValue} " +
                "gap=${single.comparisonValue - quake.comparisonValue}",
        )
        assertTrue(
            quake.comparisonValue > single.comparisonValue - SANE_COLLATERAL_GAP,
            "Grazing one ally should not cost more than the whole rest of the move is worth: " +
                "earthquake=${quake.comparisonValue} single=${single.comparisonValue}",
        )
    }

    private fun context(allyTypes: Set<String>): BattleDecisionContext {
        val actor = mon(BattleSide.ALLY, 0, setOf("ground"))
        val partner = mon(BattleSide.ALLY, 1, allyTypes)
        val opponents = listOf(mon(BattleSide.OPPONENT, 0, setOf("normal")), mon(BattleSide.OPPONENT, 1, setOf("normal")))
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.DOUBLE, turn = 2,
                pokemon = listOf(actor, partner) + opponents, field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 4 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(
                attack("earthquake", "ground", BattleMoveTargetPattern.ALL_ADJACENT, emptyList()),
                attack(
                    "single", "ground", BattleMoveTargetPattern.SELECTED_OPPONENT,
                    listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                ),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    private fun attack(
        id: String,
        typeId: String,
        pattern: BattleMoveTargetPattern,
        targets: List<BattleTargetSlot>,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = targets,
        moveDetails = BattleMoveCandidateView(
            typeId = typeId, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 100.0,
            accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = pattern,
        ),
    )

    private fun mon(side: BattleSide, slot: Int, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe_${side.name.lowercase()}_$slot", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 140, 100, 100, 100, 100)
        } else {
            publicExactStats(200, 100, 100, 100, 100, 90)
        },
    )

    private companion object {
        /**
         * How far behind the single-target move the spread one may fall once the partner is charged
         * for. Generous on purpose: the claim is only that the charge is on the same scale as the
         * thing it is subtracted from, not that it has any particular value.
         */
        const val SANE_COLLATERAL_GAP = 60.0
    }
}
