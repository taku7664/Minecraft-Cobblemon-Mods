package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.IntentOption
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.search.LocalOpponentIntentWeights
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseValue
import jbro.cobblemon.mcc.betterai.search.LocalResponseValue
import jbro.cobblemon.mcc.betterai.search.LocalSearchResponseObjective
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalOpponentIntentWeightsTest {
    private val ally = UUID(0, 1)
    private val foe = UUID(0, 2)
    private val bench = UUID(0, 3)
    private val state = BattleStateView(UUID(0, 9), BattleFormat.SINGLE, 1, listOf(
        BattlePokemonStateView(ally, BattleSide.ALLY, 0, "a", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false),
        BattlePokemonStateView(foe, BattleSide.OPPONENT, 0, "b", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false),
    ), BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2), emptyList(), emptyList())
    private val intent = OpponentIntent(foe, 0, listOf(
        IntentOption(IntentKind.ATTACK, "tackle", ally, null, 1.0, 0.7),
        IntentOption(IntentKind.PROTECT, "protect", null, null, 0.5, 0.2),
        IntentOption(IntentKind.SWITCH, null, null, bench, 0.0, 0.1),
    ))
    private fun move(id: String) = BattleActionCandidate("o:$id", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id)

    @Test
    fun `responses take the predicted chances, an unnamed one the floor, normalised`() {
        val actions = listOf(move("tackle"), move("protect"),
            BattleActionCandidate("o:switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = bench), move("guess"))
        val chances = LocalOpponentIntentWeights.probabilities(actions, listOf(intent), state)!!
        val total = 0.7 + 0.2 + 0.1 + LocalOpponentIntentWeights.UNNAMED
        assertEquals(listOf(0.7, 0.2, 0.1, LocalOpponentIntentWeights.UNNAMED).map { it / total }, chances)
        assertNull(LocalOpponentIntentWeights.probabilities(listOf(move("guess")), listOf(intent), state))
    }

    @Test
    fun `the intent share moves the robust value toward the expected one and keeps the rest`() {
        fun value(v: Double) = LocalResponseValue(v, 1.0, 0.5)
        val values = listOf(LocalOpponentResponseValue(move("tackle"), value(-10.0)), LocalOpponentResponseValue(move("protect"), value(20.0)))
        val robust = value(-8.0)
        val blended = LocalSearchResponseObjective.withIntent(robust, values, listOf(0.25, 0.75))
        val weight = LocalSearchResponseObjective.INTENT_WEIGHT
        assertEquals(-8.0 * (1 - weight) + (0.25 * -10.0 + 0.75 * 20.0) * weight, blended.value, 1e-9)
    }
}
