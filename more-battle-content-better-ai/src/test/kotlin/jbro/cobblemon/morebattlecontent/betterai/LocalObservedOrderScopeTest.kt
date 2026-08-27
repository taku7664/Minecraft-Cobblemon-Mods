package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An observed action order is evidence about the conditions it was seen in, not a standing fact.
 *
 * The projector only consults an observation when the public Speed ranges overlap, and overlap is the
 * normal case: a real opponent's Speed is known as a species range roughly 1.8x wide, while the ally's
 * is exact. That is also the position every Speed-control move is played from. Treating the
 * observation as permanent therefore closed the exact door it needed to leave open - the search
 * projected Icy Wind, Thunder Wave, Rock Tomb, Sticky Web and Tailwind into a future where the
 * opponent still moved first, so none of them could ever pay, so the search never chose one, so the
 * observation was never refuted.
 *
 * The Speed drop in the sibling foresight position separates the ranges outright and so never reached
 * this path. This is the position that does: the drop narrows the gap without closing it.
 */
class LocalObservedOrderScopeTest {
    @Test
    fun `a projected speed drop is not bound by the order observed before it`() {
        // Ally Speed is exactly 100. The opponent is publicly 120-200 and was seen moving first, which
        // at neutral stages is both true and the only thing the search can say.
        val neutral = state(opponentSpeedStage = 0)
        // One stage of Icy Wind takes the opponent to 80-133. The ally at 100 sits inside that, so the
        // ranges still overlap and the observation is what decides - or was.
        val afterDrop = state(opponentSpeedStage = -1)

        val orders = project(observedState = neutral, projectedState = afterDrop)

        assertEquals(
            setOf(listOf(BattleSide.ALLY, BattleSide.OPPONENT), listOf(BattleSide.OPPONENT, BattleSide.ALLY)),
            orders,
            "After lowering the opponent's Speed the search must price both orders. Seeing only " +
                "$orders means the pre-drop observation is still deciding the post-drop turn, which " +
                "makes every Speed-control move worthless to the search.",
        )
    }

    @Test
    fun `the order observed under the conditions that still hold is kept`() {
        // The other half of the contract. Withholding the observation whenever it is inconvenient would
        // throw away real evidence and make the search guess at orders it actually knows.
        val neutral = state(opponentSpeedStage = 0)

        val orders = project(observedState = neutral, projectedState = neutral)

        assertEquals(
            setOf(listOf(BattleSide.OPPONENT, BattleSide.ALLY)),
            orders,
            "Nothing has changed the Speed of either side, so the observation still applies and the " +
                "search must not branch an order it has evidence against.",
        )
    }

    @Test
    fun `a bigger speed drop is priced as more likely to win the order than a smaller one`() {
        // The order was previously either proven or a coin flip, with nothing in between. Every drop
        // that left the ranges overlapping therefore scored the same, so the search had no reason to
        // prefer two stages over one, paralysis over a chip, or Tailwind over nothing.
        val neutral = orderProbability(opponentSpeedStage = 0)
        val oneStage = orderProbability(opponentSpeedStage = -1)
        val twoStages = orderProbability(opponentSpeedStage = -2)

        val report = "ally 100 against a public 120-200: " +
            "neutral=%.3f oneStage=%.3f twoStages=%.3f".format(neutral, oneStage, twoStages)

        assertTrue(neutral < oneStage, "One stage has to improve on none. $report")
        assertTrue(oneStage < twoStages, "Two stages have to improve on one. $report")
        // Not a certainty either: at one stage the opponent is 80-133 and the ally sits inside it, so
        // claiming the order outright would be inventing information the ranges do not carry.
        assertTrue(oneStage in 0.01..0.99, "One stage must stay uncertain, not become proof. $report")
    }

    /** The chance the ally acts first, read off the orders the projector actually produced. */
    private fun orderProbability(opponentSpeedStage: Int): Double {
        val state = state(opponentSpeedStage)
        val source = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state(opponentSpeedStage = 0),
            candidates = listOf(move(BattleSide.ALLY)),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        val projections = PublicSingleTurnProjector.project(
            initialState = state,
            allyAction = move(BattleSide.ALLY),
            opponentAction = move(BattleSide.OPPONENT),
            sourceContext = source,
        )
        val allyFirst = projections.filter { it.order.firstOrNull() == BattleSide.ALLY }
        if (allyFirst.isEmpty()) return 0.0
        return allyFirst.first().orderProbability
    }

    private fun project(observedState: BattleStateView, projectedState: BattleStateView): Set<List<BattleSide>> {
        val source = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = observedState,
            candidates = listOf(move(BattleSide.ALLY)),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicSingleTurnProjector.project(
            initialState = projectedState,
            allyAction = move(BattleSide.ALLY),
            opponentAction = move(BattleSide.OPPONENT),
            sourceContext = source,
        ).map { it.order }.toSet()
    }

    private fun move(side: BattleSide) = BattleActionCandidate(
        actionId = "tackle_${side.name.lowercase()}",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:tackle",
        targets = listOf(
            BattleTargetSlot(if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY, 0),
        ),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 40.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        ),
    )

    private fun state(opponentSpeedStage: Int): BattleStateView {
        val ally = pokemon(ALLY_ID, BattleSide.ALLY, BattleIntegerRange(100, 100), 0)
        val opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, BattleIntegerRange(120, 200), opponentSpeedStage)
        return BattleStateView(
            battleId = BATTLE_ID,
            format = BattleFormat.SINGLE,
            turn = 5,
            pokemon = listOf(ally, opponent),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
            observedEvents = emptyList(),
            inferences = listOf(
                BattleInferenceView(
                    subjectPokemonId = OPPONENT_ID,
                    categoryId = "observed_action_order",
                    candidateId = "BEFORE_AT_SAME_BASE_PRIORITY",
                    confidence = BattleInferenceConfidence.CONFIRMED,
                    basis = setOf(BattleInferenceBasis.ACTION_ORDER),
                    evidenceEventSequences = listOf(1L, 2L),
                    relatedPokemonId = ALLY_ID,
                ),
            ),
        )
    }

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        speed: BattleIntegerRange,
        speedStage: Int,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = if (speedStage == 0) emptyMap() else mapOf("cobblemon:speed" to speedStage),
        knownMoveIds = setOf("cobblemon:tackle"),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(150, 150),
            attack = BattleIntegerRange(110, 110),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(110, 110),
            specialDefence = BattleIntegerRange(100, 100),
            speed = speed,
            knowledge = if (side == BattleSide.ALLY) {
                BattleCombatStatKnowledge.EXACT_OWN
            } else {
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE
            },
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    }
}
