package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.IntentOption
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.search.LocalOpponentIntentWeights
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalPublicJointIntentConditioningTest {
    @Test
    fun `repeated public focus fire changes joint intent without declaring split attacks impossible`() {
        val chances = probabilities(events(3))
        assertTrue(chances[0] + chances[3] > 0.5)
        assertTrue(chances[1] > 0.0 && chances[2] > 0.0)
        assertEquals(1.0, chances.sum(), 1e-9)
        assertTrue(probabilities(events(6))[0] > chances[0], "Every complete retained pair must contribute")
    }

    @Test
    fun `a partial history and fewer than three complete pairs keep the independent prior`() {
        for (history in listOf(events(2), events(3).drop(1), events(3).filter { it.actorPokemonId == FOE_ZERO })) {
            assertEquals(List(4) { 0.25 }, probabilities(history))
        }
    }

    @Test
    fun `current partial turn and duplicated actor actions are not counted as public pairs`() {
        val duplicate = events(3).toMutableList().apply {
            add(BattleObservedEventView(7, 3, BattleObservedEventKind.MOVE_USED, FOE_ZERO,
                listOf(ALLY_ZERO), publicValueId = "tackle"))
        }
        assertEquals(List(4) { 0.25 }, probabilities(duplicate.sortedBy { it.sequence }))
        assertEquals(List(4) { 0.25 }, probabilities(events(3), turn = 3))
    }

    @Test
    fun `a public field change and a different opponent actor cannot inherit the old coordination`() {
        val changed = events(3) + BattleObservedEventView(7, 3, BattleObservedEventKind.FIELD_EFFECT_CHANGED,
            publicValueId = "trickroom")
        assertEquals(List(4) { 0.25 }, probabilities(changed))
        val stranger = UUID(0, 99)
        val history = events(3).map { event -> if (event.actorPokemonId == FOE_ONE)
            BattleObservedEventView(event.sequence, event.turn, event.kind, stranger, event.targetPokemonIds,
                publicValueId = event.publicValueId) else event }
        assertEquals(List(4) { 0.25 }, probabilities(history, extra = listOf(mon(stranger, BattleSide.OPPONENT, null))))
    }

    @Test
    fun `coordination conditioning preserves the unrelated switch probability mass`() {
        val actions = actions() + joint(
            BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = BENCH),
            attack(1, 0))
        val intents = intents().map { intent -> if (intent.activeSlot == 0) intent.copy(options =
            intent.options.map { it.copy(probability = 0.4) } + IntentOption(IntentKind.SWITCH, null, null, BENCH, 0.0, 0.2)) else intent }
        val base = requireNotNull(LocalOpponentIntentWeights.probabilities(actions, intents, state(emptyList())))
        val learned = requireNotNull(LocalOpponentIntentWeights.probabilities(actions, intents, state(events(3))))
        assertEquals(base.last(), learned.last(), 1e-9)
        assertTrue(learned[0] > base[0])
        assertTrue(learned.all { it > 0.0 })
        assertEquals(1.0, learned.sum(), 1e-9)
    }

    private fun probabilities(history: List<BattleObservedEventView>, turn: Int = 7,
        extra: List<BattlePokemonStateView> = emptyList()): List<Double> = requireNotNull(
        LocalOpponentIntentWeights.probabilities(actions(), intents(), state(history, turn, extra)))

    private fun state(history: List<BattleObservedEventView>, turn: Int = 7, extra: List<BattlePokemonStateView> = emptyList()) =
        BattleStateView(UUID(0, 9), BattleFormat.DOUBLE, turn, listOf(
            mon(ALLY_ZERO, BattleSide.ALLY, 0), mon(ALLY_ONE, BattleSide.ALLY, 1),
            mon(FOE_ZERO, BattleSide.OPPONENT, 0), mon(FOE_ONE, BattleSide.OPPONENT, 1)) + extra,
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2), history, emptyList())

    private fun events(count: Int): List<BattleObservedEventView> = (1..count).flatMap { turn ->
        listOf(BattleObservedEventView((turn * 2 - 1).toLong(), turn, BattleObservedEventKind.MOVE_USED,
            FOE_ZERO, listOf(ALLY_ZERO), publicValueId = "tackle"),
            BattleObservedEventView((turn * 2).toLong(), turn, BattleObservedEventKind.MOVE_USED,
                FOE_ONE, listOf(ALLY_ZERO), publicValueId = "tackle"))
    }

    private fun intents(): List<OpponentIntent> = listOf(FOE_ZERO, FOE_ONE).mapIndexed { slot, id ->
        OpponentIntent(id, slot, listOf(ALLY_ZERO, ALLY_ONE).map { target ->
            IntentOption(IntentKind.ATTACK, "tackle", target, null, 1.0, 0.5)
        })
    }

    private fun actions() = (0..1).flatMap { target -> (0..1).map { partnerTarget ->
        joint(attack(0, target), attack(1, partnerTarget))
    } }

    private fun attack(slot: Int, target: Int) = BattleActionCandidate("attack-$slot-$target", BattleActionKind.USE_MOVE,
        actorSlot = slot, moveSlot = 0, moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.ALLY, target)))

    private fun joint(first: BattleActionCandidate, second: BattleActionCandidate) = BattleActionCandidate(
        "${first.actionId}+${second.actionId}", BattleActionKind.COMPOSITE,
        componentActionIds = listOf(first.actionId, second.actionId), componentActions = listOf(first, second))

    private fun mon(id: UUID, side: BattleSide, slot: Int?) = BattlePokemonStateView(id, side, slot, "probe", null, 50,
        1.0, null, emptyMap(), setOf("tackle"), null, null, false)

    private companion object {
        val ALLY_ZERO = UUID(0, 1)
        val ALLY_ONE = UUID(0, 2)
        val FOE_ZERO = UUID(0, 3)
        val FOE_ONE = UUID(0, 4)
        val BENCH = UUID(0, 5)
    }
}
