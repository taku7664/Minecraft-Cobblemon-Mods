package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.IntentOption
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentRepeats
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalOpponentRepeatsTest {
    private val ally = UUID(0, 1)
    private val foe = UUID(0, 2)
    private val ghost = UUID(0, 3)

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = slot, speciesId = "showdown:test", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"))

    private fun state(turn: Int, foeActive: UUID, events: List<BattleObservedEventView>) = BattleStateView(
        battleId = UUID(0, 99), format = BattleFormat.SINGLE, turn = turn,
        pokemon = listOf(pokemon(ally, BattleSide.ALLY, 0), pokemon(foe, BattleSide.OPPONENT, if (foeActive == foe) 0 else null),
            pokemon(ghost, BattleSide.OPPONENT, if (foeActive == ghost) 0 else null)),
        field = BattleFieldStateView.empty(), remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
        observedEvents = events, inferences = emptyList())

    private val intent = OpponentIntent(foe, 0, listOf(
        IntentOption(IntentKind.ATTACK, "gunkshot", ally, null, 0.5, 0.7),
        IntentOption(IntentKind.SWITCH, null, null, ghost, 0.1, 0.3),
    ))

    @Test
    fun `a switch made in a matchup is expected the next time that matchup comes up`() {
        val repeats = LocalOpponentRepeats()
        repeats.observe(state(1, foe, emptyList()))
        // Turn 1: the foe switched to the ghost instead of moving.
        val switched = BattleObservedEventView(1, 1, BattleObservedEventKind.SWITCHED, ghost, actorSlot = 0)
        repeats.observe(state(2, ghost, listOf(switched)))
        val later = state(5, foe, listOf(switched))
        val first = repeats.apply(listOf(intent), later).single().byAction()
        assertEquals(0.3 * 0.5 + 0.5, first.getValue("switch:$ghost"), 1e-9)
        assertEquals(0.7 * 0.5, first.getValue("move:gunkshot"), 1e-9)
    }

    @Test
    fun `a replacement after a knockout is no choice`() {
        val repeats = LocalOpponentRepeats()
        repeats.observe(state(1, foe, emptyList()))
        val events = listOf(
            BattleObservedEventView(1, 1, BattleObservedEventKind.FAINTED, foe, actorSlot = 0),
            BattleObservedEventView(2, 1, BattleObservedEventKind.SWITCHED, ghost, actorSlot = 0),
        )
        repeats.observe(state(2, ghost, events))
        val unchanged = repeats.apply(listOf(intent), state(5, foe, events)).single()
        assertTrue(unchanged.options.zip(intent.options).all { (a, b) -> a.probability == b.probability })
    }
}
