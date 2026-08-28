package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A screen can take a knockout off the table, and the ranking has to hear about it.
 *
 * The damage half of the score was always mechanics-aware, by a route that is easy to miss: the
 * scorer publishes an unclamped pressure from the facts, the outcome evaluator subtracts exactly
 * that term and substitutes the projector's figure, and the projector applies the mechanics kernel.
 * Two files, one term.
 *
 * The knockout half had no such cancellation. It read `standardKnockoutAssessment` straight, and
 * that field is contractually the plain Showdown base projection with every dynamic modifier
 * excluded - so behind a Light Screen the AI priced the damage correctly and still believed the
 * attack was a guaranteed knockout. That is not a smaller number, it is a wrong premise: whether a
 * patient line is affordable and whether it is time to switch are both decided on it.
 *
 * The facts themselves are deliberately left alone here. Making them carry the screen would be the
 * easy fix and the wrong one - `LocalTacticalBrainSimulationTest` supplies its own facts, the
 * outcome projector compensates for exactly that case, and the contract published to Router says
 * these fields exclude field modifiers.
 */
class LocalScreenAndWeatherFactsTest {
    @Test
    fun `a screen removes the knockout bonus the ranking would have paid`() {
        val plain = knockoutUtility(sideConditions = emptyList())
        assertTrue(plain > 0.0, "Without the screen this is a knockout worth paying for, was $plain.")
        val screened = knockoutUtility(sideConditions = listOf(BattleTimedEffectView("lightscreen", 5)))
        assertEquals(
            0.0, screened, 1.0e-9,
            "Behind a Light Screen the same hit cannot kill, so there is no knockout to pay for.",
        )
    }

    @Test
    fun `a reflect leaves a special attack's knockout alone`() {
        val plain = knockoutUtility(sideConditions = emptyList())
        val reflected = knockoutUtility(sideConditions = listOf(BattleTimedEffectView("reflect", 5)))
        assertEquals(plain, reflected, 1.0e-9, "Reflect is the physical screen.")
    }

    @Test
    fun `rain does the same to a fire attack`() {
        val dry = knockoutUtility(typeId = "fire")
        assertTrue(dry > 0.0, "The Fire attack kills in clear weather.")
        val wet = knockoutUtility(typeId = "fire", weather = BattleTimedEffectView("raindance", 5))
        assertEquals(0.0, wet, 1.0e-9, "Halved by the rain it no longer reaches, so the knockout goes.")
    }

    @Test
    fun `an ordinary turn still reads the declared assessment`() {
        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context()).candidates.single().facts,
        )
        assertEquals(
            BattleKnockoutAssessment.GUARANTEED, facts.standardKnockoutAssessment,
            "With nothing modifying the hit the facts are the answer, and no second projection is paid for.",
        )
    }

    private fun knockoutUtility(
        sideConditions: List<BattleTimedEffectView> = emptyList(),
        weather: BattleTimedEffectView? = null,
        typeId: String = "psychic",
    ): Double {
        val calculated = PublicBattleTacticalCalculator.calculate(context(sideConditions, weather, typeId))
        return LocalTacticalScorer.knockoutUtility(
            candidate = calculated.candidates.single(),
            context = calculated,
        )
    }

    private fun context(
        sideConditions: List<BattleTimedEffectView> = emptyList(),
        weather: BattleTimedEffectView? = null,
        typeId: String = "psychic",
    ): BattleDecisionContext {
        val ally = mon(BattleSide.ALLY)
        val opponent = mon(BattleSide.OPPONENT)
        val move = BattleActionCandidate(
            actionId = "probe", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:probe", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = typeId, damageCategory = BattleMoveDamageCategory.SPECIAL, power = 150.0,
                accuracy = 100.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent),
                field = BattleFieldStateView(
                    weather = weather,
                    terrain = null,
                    roomEffects = emptyList(),
                    globalEffects = emptyList(),
                    sideConditions = mapOf(
                        BattleSide.ALLY to emptyList(),
                        BattleSide.OPPONENT to sideConditions,
                    ),
                ),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    /**
     * Exact stats on both sides, so the sixteen rolls are the only spread left and a knockout
     * assessment is a statement about the screen rather than about the stat ranges.
     *
     * The defender is deliberately sized so the plain hit kills on every roll and the halved one on
     * none. Anything in between would let the test pass on a scaled average, which is the reading
     * this is here to rule out.
     */
    private fun mon(side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
        statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 150, 100, 100)
        } else {
            publicExactStats(100, 100, 100, 100, 75, 100)
        },
    )
}
