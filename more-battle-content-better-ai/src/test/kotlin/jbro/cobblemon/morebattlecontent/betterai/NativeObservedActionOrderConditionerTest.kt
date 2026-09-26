package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeExecutedMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedActionOrderConditioner
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedActionOrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeObservedActionOrderConditionerTest {
    @Test
    fun `advanced rejects a world whose native order contradicts one public turn`() {
        val result = NativeObservedActionOrderConditioner.evaluate(
            BattleTrainerTier.ADVANCED,
            state(eventsForTurn(turn = 1, first = OPPONENT, second = ALLY)),
            frame(nativeOrder(turn = 1, first = ALLY, second = OPPONENT)),
        )

        assertEquals(NativeObservedActionOrderStatus.CONTRADICTED, result.status)
        assertEquals(setOf(1), result.comparableTurns)
    }

    @Test
    fun `introductory deliberately does not learn speed worlds from action order`() {
        val result = NativeObservedActionOrderConditioner.evaluate(
            BattleTrainerTier.INTRODUCTORY,
            state(eventsForTurn(turn = 1, first = OPPONENT, second = ALLY)),
            frame(nativeOrder(turn = 1, first = ALLY, second = OPPONENT)),
        )

        assertEquals(NativeObservedActionOrderStatus.DISABLED, result.status)
        assertEquals(emptySet<Int>(), result.comparableTurns)
    }

    @Test
    fun `standard waits for two distinct comparable turns before narrowing worlds`() {
        val oneTurn = NativeObservedActionOrderConditioner.evaluate(
            BattleTrainerTier.STANDARD,
            state(eventsForTurn(turn = 1, first = OPPONENT, second = ALLY)),
            frame(nativeOrder(turn = 1, first = ALLY, second = OPPONENT)),
        )
        val twoTurns = NativeObservedActionOrderConditioner.evaluate(
            BattleTrainerTier.STANDARD,
            state(
                eventsForTurn(turn = 1, first = OPPONENT, second = ALLY) +
                    eventsForTurn(turn = 2, first = OPPONENT, second = ALLY, firstSequence = 3),
                turn = 2,
            ),
            frame(
                nativeOrder(turn = 1, first = ALLY, second = OPPONENT) +
                    nativeOrder(turn = 2, first = ALLY, second = OPPONENT),
            ),
        )

        assertEquals(NativeObservedActionOrderStatus.INSUFFICIENT_EVIDENCE, oneTurn.status)
        assertEquals(NativeObservedActionOrderStatus.CONTRADICTED, twoTurns.status)
        assertEquals(setOf(1, 2), twoTurns.comparableTurns)
    }

    @Test
    fun `different base priority and repeated actors remain non evidence`() {
        val differentPriority = listOf(
            orderEvent(1, 1, OPPONENT, "growl", priority = 1),
            orderEvent(2, 1, ALLY, "tackle", priority = 0),
        )
        val repeatedActor = listOf(
            orderEvent(1, 1, OPPONENT, "growl"),
            orderEvent(2, 1, OPPONENT, "protect"),
            orderEvent(3, 1, ALLY, "tackle"),
        )

        assertEquals(
            NativeObservedActionOrderStatus.INSUFFICIENT_EVIDENCE,
            NativeObservedActionOrderConditioner.evaluate(
                BattleTrainerTier.BOSS,
                state(differentPriority),
                frame(nativeOrder(1, OPPONENT, ALLY)),
            ).status,
        )
        assertEquals(
            NativeObservedActionOrderStatus.INSUFFICIENT_EVIDENCE,
            NativeObservedActionOrderConditioner.evaluate(
                BattleTrainerTier.BOSS,
                state(repeatedActor),
                frame(
                    listOf(
                        NativeExecutedMoveFrame(1, OPPONENT.toString(), "growl"),
                        NativeExecutedMoveFrame(1, OPPONENT.toString(), "protect"),
                        NativeExecutedMoveFrame(1, ALLY.toString(), "tackle"),
                    ),
                ),
            ).status,
        )
    }

    private fun eventsForTurn(
        turn: Int,
        first: UUID,
        second: UUID,
        firstSequence: Long = 1,
    ) = listOf(
        orderEvent(firstSequence, turn, first, move(first)),
        orderEvent(firstSequence + 1, turn, second, move(second)),
    )

    private fun orderEvent(
        sequence: Long,
        turn: Int,
        actor: UUID,
        move: String,
        priority: Int = 0,
    ) = BattleObservedEventView(
        sequence = sequence,
        turn = turn,
        kind = BattleObservedEventKind.ACTION_ORDER,
        actorPokemonId = actor,
        publicValueId = move,
        baseMovePriority = priority,
    )

    private fun nativeOrder(turn: Int, first: UUID, second: UUID) = listOf(
        NativeExecutedMoveFrame(turn, first.toString(), move(first)),
        NativeExecutedMoveFrame(turn, second.toString(), move(second)),
    )

    private fun move(actor: UUID) = if (actor == ALLY) "tackle" else "growl"

    private fun state(
        events: List<BattleObservedEventView>,
        turn: Int = 1,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(pokemon(ALLY, BattleSide.ALLY), pokemon(OPPONENT, BattleSide.OPPONENT)),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "showdown:mew",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
    )

    private fun frame(order: List<NativeExecutedMoveFrame>) = NativeBattleFrame(
        snapshotJson = "snapshot",
        turn = order.maxOfOrNull(NativeExecutedMoveFrame::turn)?.plus(1) ?: 1,
        requestState = "move",
        ended = false,
        p1Active = emptyList(),
        p2Active = emptyList(),
        p1Team = emptyList(),
        p2Team = emptyList(),
        p1RequestJson = "{}",
        p2RequestJson = "{}",
        log = emptyList(),
        executedMoveOrder = order,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
    }
}
