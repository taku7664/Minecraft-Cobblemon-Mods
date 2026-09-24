package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeShowdownChoiceEncoderTest {
    @Test
    fun `single move and switch use native request slots`() {
        assertEquals(
            "move 1",
            NativeShowdownChoiceEncoder.encode(move("ally_move", actorSlot = 0, moveSlot = 0), BattleSide.ALLY, frame),
        )
        assertEquals(
            "switch 3",
            NativeShowdownChoiceEncoder.encode(
                BattleActionCandidate(
                    actionId = "switch",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = 0,
                    switchPokemonId = ALLY_BENCH,
                ),
                BattleSide.ALLY,
                frame,
            ),
        )
    }

    @Test
    fun `double composite orders actor slots and encodes relative targets`() {
        val slotOne = move(
            id = "slot_one",
            actorSlot = 1,
            moveSlot = 1,
            moveId = "ally_support",
            targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)),
            mechanic = "tera",
        )
        val slotZero = move(
            id = "slot_zero",
            actorSlot = 0,
            moveSlot = 0,
            moveId = "ally_move",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)),
        )
        val composite = BattleActionCandidate(
            actionId = "joint",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf(slotOne.actionId, slotZero.actionId),
            componentActions = listOf(slotOne, slotZero),
        )

        assertEquals(
            "move 1 2, move 2 -1 terastallize",
            NativeShowdownChoiceEncoder.encode(composite, BattleSide.ALLY, frame),
        )
    }

    @Test
    fun `opponent targets are relative to the opponent side`() {
        val opponentMove = move(
            id = "opponent_move",
            actorSlot = 0,
            moveSlot = 0,
            moveId = "opponent_move",
            targets = listOf(BattleTargetSlot(BattleSide.ALLY, 1)),
        )

        assertEquals(
            "move 1 2",
            NativeShowdownChoiceEncoder.encode(opponentMove, BattleSide.OPPONENT, frame),
        )
    }

    @Test
    fun `major mechanics map to Showdown choice suffixes`() {
        assertEquals(
            "move 1 mega",
            NativeShowdownChoiceEncoder.encode(
                move("mega", actorSlot = 0, moveSlot = 0, moveId = "ally_move", mechanic = "mega"),
                BattleSide.ALLY,
                frame,
            ),
        )
        assertEquals(
            "move 1 dynamax",
            NativeShowdownChoiceEncoder.encode(
                move("max", actorSlot = 0, moveSlot = 0, moveId = "ally_move", mechanic = "dynamax"),
                BattleSide.ALLY,
                frame,
            ),
        )
    }

    @Test
    fun `double pass keeps its actor slot in the joint choice`() {
        val pass = BattleActionCandidate(
            actionId = "slot-zero-pass",
            kind = BattleActionKind.WAIT,
            actorSlot = 0,
        )
        val slotOneMove = move(
            id = "slot-one-move",
            actorSlot = 1,
            moveSlot = 0,
            moveId = "ally_other",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        )
        val composite = BattleActionCandidate(
            actionId = "pass-and-move",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf(slotOneMove.actionId, pass.actionId),
            componentActions = listOf(slotOneMove, pass),
        )

        assertEquals(
            "pass, move 1 1",
            NativeShowdownChoiceEncoder.encode(composite, BattleSide.ALLY, frame),
        )
    }

    @Test
    fun `candidate move must agree with the native active slot`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            NativeShowdownChoiceEncoder.encode(
                move("lie", actorSlot = 0, moveSlot = 0, moveId = "different_move"),
                BattleSide.ALLY,
                frame,
            )
        }

        assertEquals("Candidate move differentmove disagrees with native slot allymove", failure.message)
    }

    private fun move(
        id: String,
        actorSlot: Int,
        moveSlot: Int,
        moveId: String = id,
        targets: List<BattleTargetSlot> = emptyList(),
        mechanic: String? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = moveSlot,
        moveId = moveId,
        targets = targets,
        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
    )

    private val frame = NativeBattleFrame(
        snapshotJson = "{}",
        turn = 1,
        requestState = "move",
        ended = false,
        p1Active = listOf(pokemon(ALLY_ACTIVE_ZERO, 0, "ally_move", "ally_second"), pokemon(ALLY_ACTIVE_ONE, 1,
            "ally_other", "ally_support")),
        p2Active = listOf(pokemon(OPPONENT_ACTIVE_ZERO, 0, "opponent_move"), pokemon(OPPONENT_ACTIVE_ONE, 1,
            "opponent_other")),
        p1Team = listOf(
            pokemon(ALLY_ACTIVE_ZERO, 0, "ally_move", "ally_second"),
            pokemon(ALLY_ACTIVE_ONE, 1, "ally_other", "ally_support"),
            pokemon(ALLY_BENCH, null, "bench_move"),
        ),
        p2Team = listOf(
            pokemon(OPPONENT_ACTIVE_ZERO, 0, "opponent_move"),
            pokemon(OPPONENT_ACTIVE_ONE, 1, "opponent_other"),
        ),
        log = emptyList(),
    )

    private fun pokemon(uuid: UUID, activeSlot: Int?, vararg moves: String) = NativePokemonFrame(
        uuid = uuid.toString(),
        species = "mew",
        hp = 100,
        maxHp = 100,
        status = "",
        ability = "synchronize",
        item = "",
        types = listOf("Psychic"),
        boosts = emptyMap(),
        volatiles = emptyList(),
        moves = moves.map { NativeMoveFrame(it, 10, 10, false) },
        activeSlot = activeSlot,
    )

    private companion object {
        val ALLY_ACTIVE_ZERO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val ALLY_ACTIVE_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val ALLY_BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000000103")
        val OPPONENT_ACTIVE_ZERO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val OPPONENT_ACTIVE_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
    }
}
