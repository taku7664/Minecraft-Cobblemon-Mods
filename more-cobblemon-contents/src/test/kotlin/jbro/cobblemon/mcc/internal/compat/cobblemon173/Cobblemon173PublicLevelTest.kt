package jbro.cobblemon.mcc.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadTerminationReason
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Cobblemon173PublicLevelTest {
    @Test
    fun `omitted level means 100 in valid public Showdown details`() {
        for (details in listOf("Dialga", "Dialga, M", "Dialga, shiny",
            "Dialga, $FOE, shiny, tera:Steel")) {
            val presented = Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(hiddenSnapshot(), details)
            assertEquals(100, presented.level, details)
            assertEquals("showdown:dialga", presented.speciesId)
        }
    }

    @Test
    fun `explicit public levels override the hidden resolver level`() {
        for (level in listOf(1, 50, 95, 99, 100)) {
            val presented = Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(
                hiddenSnapshot(), "Pikachu, $FOE, L$level, F",
            )
            assertEquals(level, presented.level)
        }
    }

    @Test
    fun `absent or malformed public details never borrow the hidden level or default to 100`() {
        for (details in listOf(null, "", " ", ", M", "Pikachu, Lbad", "Pikachu, L0",
            "Pikachu, L50, L60", "Pikachu, garbage", "Pikachu,, M")) {
            assertNull(Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(hiddenSnapshot(), details).level, details)
        }
    }

    @Test
    fun `level 100 public presentation allows lookahead to visit nodes`() {
        val presented = Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(hiddenSnapshot(), "Dialga, $FOE")
            .toView(previous = null, refreshPublicIdentity = true)
        val ally = BattlePokemonStateView(ALLY, BattleSide.ALLY, 0, "showdown:pikachu", null,
            100, 1.0, null, emptyMap(), setOf("tackle"), null, null, false, setOf("normal"),
            BattleCombatStatRangesView.exact(300, 200, 200, 200, 200, 200))
        val move = BattleActionCandidate("tackle", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10))
        val state = BattleStateView(BATTLE, BattleFormat.SINGLE, 3, listOf(ally, presented),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val context = PublicBattleTacticalCalculator.calculate(BattleDecisionContext(BATTLE, state, listOf(move), Long.MAX_VALUE))
        val profile = BattleTrainerProfile.balanced(1).let { it.copy(difficulty = it.difficulty.copy(lookaheadPlies = 1)) }
        val ranked = LocalBattleActionPolicy.rank(context, null, profile)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranked, context, profile, clockMillis = { 0L })

        assertTrue(result.nodesVisited > 0, result.toString())
        assertEquals(1, result.depthCompleted)
        assertNotEquals(LocalLookaheadTerminationReason.PUBLIC_DATA_INCOMPLETE, result.terminationReason)
    }

    private fun hiddenSnapshot() = Cobblemon173PublicPokemonSnapshot(
        battlePokemonId = FOE, side = BattleSide.OPPONENT, activeSlot = 0,
        speciesId = "cobblemon:zoroark", formId = "hisui", level = 73, hpFraction = 1.0,
        statusId = null, statStages = emptyMap(), fainted = false, knownTypeIds = setOf("steel", "dragon"),
        combatStats = BattleCombatStatRangesView(
            BattleIntegerRange(300, 300), BattleIntegerRange(200, 200), BattleIntegerRange(200, 200),
            BattleIntegerRange(200, 200), BattleIntegerRange(200, 200), BattleIntegerRange(100, 100),
            BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
    )

    private companion object {
        val BATTLE = UUID.fromString("00000000-0000-0000-0000-000000001000")
        val ALLY = UUID.fromString("00000000-0000-0000-0000-000000001001")
        val FOE = UUID.fromString("00000000-0000-0000-0000-000000001002")
    }
}
