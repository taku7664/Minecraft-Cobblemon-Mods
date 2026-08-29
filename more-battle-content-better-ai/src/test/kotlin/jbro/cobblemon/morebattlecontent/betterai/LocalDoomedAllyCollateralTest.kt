package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Health the opponent is already certain to take is not charged for a second time.
 *
 * pokeemerald-expansion #6357 names the two ways a doubles AI gets friendly fire wrong, and refusing
 * it whenever a partner stands in the blast is one of them. A partner the opponent is publicly
 * certain to knock out this turn has no health left to protect: declining the spread move buys
 * nothing and gives up the second target for it.
 *
 * The discount is deliberately narrow. It needs a *revealed* opposing move that reaches the partner's
 * remaining health - never an inference about a hidden set - and it splits on the turn order, because
 * a partner struck before it has acted loses a turn as well as health and that part is real.
 */
class LocalDoomedAllyCollateralTest {
    /**
     * The price of the collateral itself, isolated from everything else the reveal changes.
     *
     * Revealing an opposing move does not only make the partner doomed - it hands the search a reply
     * to plan against, so every candidate's value moves. Comparing the spread move's raw score before
     * and after the reveal measures that too, and drowns the thing being tested.
     *
     * What is actually the collateral charge is the gap between an ally that can be hit and one that
     * cannot, held at the same knowledge. Taking that gap in both worlds cancels the reveal.
     */
    private fun collateralCharge(opponentCanKillPartner: Boolean): Double =
        spreadValue(opponentCanKillPartner, partnerTypes = setOf("flying")) -
            spreadValue(opponentCanKillPartner, partnerTypes = setOf("normal"))

    @Test
    fun `a partner already being knocked out costs less to catch in the blast`() {
        val ordinary = collateralCharge(opponentCanKillPartner = false)
        val doomed = collateralCharge(opponentCanKillPartner = true)
        assertTrue(ordinary > 0.0, "A grounded partner in the blast has to cost something, was $ordinary.")
        assertTrue(
            doomed < ordinary,
            "Health the opponent is publicly certain to take this turn is not health this move " +
                "destroys: doomed=$doomed ordinary=$ordinary",
        )
    }

    @Test
    fun `the discount is a discount rather than a licence`() {
        val doomed = collateralCharge(opponentCanKillPartner = true)
        assertTrue(
            doomed > 0.0,
            "Reading one turn ahead is not free, so a doomed partner still costs something: $doomed",
        )
    }

    private fun spreadValue(
        opponentCanKillPartner: Boolean,
        partnerTypes: Set<String> = setOf("normal"),
    ): Double {
        val breakdown = LocalDecisionInstrumentation.inspect(
            context(opponentCanKillPartner, partnerTypes),
        )
        return requireNotNull(
            breakdown.candidates.singleOrNull { it.actionId == "earthquake" },
        ) { "The spread move was not ranked at all." }.comparisonValue
    }

    private fun context(
        opponentCanKillPartner: Boolean,
        partnerTypes: Set<String>,
    ): BattleDecisionContext {
        val actor = mon(BattleSide.ALLY, 0, setOf("ground"), hpFraction = 1.0)
        // The partner is left on a sliver so a revealed opposing move can be a certain knockout on it
        // without having to be an implausible attack.
        val partner = mon(BattleSide.ALLY, 1, partnerTypes, hpFraction = 0.08)
        val opponents = listOf(
            mon(BattleSide.OPPONENT, 0, setOf("normal"), hpFraction = 1.0),
            mon(BattleSide.OPPONENT, 1, setOf("normal"), hpFraction = 1.0),
        )
        val revealed = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 120.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        )
        val catalog = BattlePublicActionCatalogView(
            if (opponentCanKillPartner) {
                opponents.map { opponent ->
                    BattlePokemonActionCatalogView(
                        opponent.battlePokemonId,
                        listOf(
                            BattlePublicMoveOptionView(
                                moveId = "cobblemon:slam",
                                details = revealed,
                                knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                            ),
                        ),
                        moveSetComplete = false,
                    )
                }
            } else {
                emptyList()
            },
        )
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.DOUBLE, turn = 3,
                pokemon = listOf(actor, partner) + opponents,
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 4 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(
                attack("earthquake", BattleMoveTargetPattern.ALL_ADJACENT, emptyList()),
                attack(
                    "single", BattleMoveTargetPattern.SELECTED_OPPONENT,
                    listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                ),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = catalog,
        )
    }

    private fun attack(
        id: String,
        pattern: BattleMoveTargetPattern,
        targets: List<BattleTargetSlot>,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = targets,
        moveDetails = BattleMoveCandidateView(
            typeId = "ground", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 100.0,
            accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = pattern,
        ),
    )

    private fun mon(side: BattleSide, slot: Int, types: Set<String>, hpFraction: Double) =
        BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
            speciesId = "cobblemon:probe_${side.name.lowercase()}_$slot", formId = null, level = 50,
            hpFraction = hpFraction, statusId = null, statStages = emptyMap(),
            knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null, fainted = false,
            knownTypeIds = types,
            combatStats = if (side == BattleSide.ALLY) {
                BattleCombatStatRangesView.exact(200, 140, 100, 100, 100, 100)
            } else {
                publicExactStats(200, 140, 100, 100, 100, 130)
            },
        )
}
