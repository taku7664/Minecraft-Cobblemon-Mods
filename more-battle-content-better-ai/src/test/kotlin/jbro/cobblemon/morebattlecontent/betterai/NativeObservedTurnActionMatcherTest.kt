package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeObservedTurnActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeObservedTurnActionMatcherTest {
    @Test
    fun `revealed single move keeps only the matching opponent command`() {
        val actions = listOf(
            move("growl", 0),
            move("tackle", 0),
            switch(BENCH, 0),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            actions,
            listOf(event(1, BattleObservedEventKind.MOVE_USED, OPPONENT_A, "growl", actorSlot = 0)),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(listOf("move-growl-0-base"), result.actions.map { it.actionId })
    }

    @Test
    fun `direct switch is matched by incoming identity`() {
        val actions = listOf(move("growl", 0), switch(BENCH, 0))

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            actions,
            listOf(event(1, BattleObservedEventKind.SWITCHED, BENCH, actorSlot = 0)),
        )

        assertEquals(listOf("switch-$BENCH-0"), result.actions.map { it.actionId })
    }

    @Test
    fun `pivot move owns its slot even when an incoming switch is also observed`() {
        val actions = listOf(move("uturn", 0), switch(BENCH, 0))
        val events = listOf(
            event(1, BattleObservedEventKind.MOVE_USED, OPPONENT_A, "uturn", actorSlot = 0),
            event(2, BattleObservedEventKind.SWITCHED, BENCH, actorSlot = 0),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            actions,
            events,
        )

        assertEquals(listOf("move-uturn-0-base"), result.actions.map { it.actionId })
    }

    @Test
    fun `a replacement after another side moves does not become the unobserved command`() {
        val actions = listOf(move("growl", 0), move("tackle", 0), switch(BENCH, 0))
        val events = listOf(
            event(1, BattleObservedEventKind.MOVE_USED, ALLY, "tackle", actorSlot = 0),
            event(2, BattleObservedEventKind.SWITCHED, BENCH, actorSlot = 0),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            actions,
            events,
        )

        assertEquals(actions, result.actions)
    }

    @Test
    fun `double observations match components by actor slot instead of list order`() {
        val exact = joint(move("taunt", 0), move("protect", 1))
        val reversed = joint(move("protect", 0), move("taunt", 1))
        val events = listOf(
            event(2, BattleObservedEventKind.MOVE_USED, OPPONENT_B, "protect", actorSlot = 1),
            event(1, BattleObservedEventKind.MOVE_USED, OPPONENT_A, "taunt", actorSlot = 0),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.DOUBLE,
            BattleSide.OPPONENT,
            frame(single = false),
            listOf(reversed, exact),
            events,
        )

        assertEquals(listOf(exact.actionId), result.actions.map { it.actionId })
    }

    @Test
    fun `double selected target keeps only the command aimed at the observed Pokemon`() {
        val leftTarget = move("tackle", 0, target = BattleTargetSlot(BattleSide.ALLY, 0))
        val rightTarget = move("tackle", 0, target = BattleTargetSlot(BattleSide.ALLY, 1))
        val observed = event(
            sequence = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actor = OPPONENT_A,
            value = "tackle",
            actorSlot = 0,
            targets = listOf(ALLY_B),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.DOUBLE,
            BattleSide.OPPONENT,
            frame(single = false),
            listOf(leftTarget, rightTarget),
            listOf(observed),
        )

        assertEquals(listOf(rightTarget.actionId), result.actions.map { it.actionId })
    }

    @Test
    fun `public tera reveal distinguishes the tera command from the same base move`() {
        val base = move("flamethrower", 0)
        val tera = move("flamethrower", 0, "tera")
        val events = listOf(
            event(1, BattleObservedEventKind.TERA_TYPE_REVEALED, OPPONENT_A, "fire", actorSlot = 0),
            event(2, BattleObservedEventKind.MOVE_USED, OPPONENT_A, "flamethrower", actorSlot = 0),
        )

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            listOf(base, tera),
            events,
        )

        assertEquals(listOf(tera.actionId), result.actions.map { it.actionId })
    }

    @Test
    fun `an unobserved action remains ambiguous instead of being invented`() {
        val actions = listOf(move("growl", 0), move("tackle", 0), switch(BENCH, 0))

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.SINGLE,
            BattleSide.OPPONENT,
            frame(single = true),
            actions,
            emptyList(),
        )

        assertEquals(actions, result.actions)
    }

    @Test
    fun `absence of a public tera reveal rejects tera even when the move never executes`() {
        val base = move("tackle", 0)
        val tera = move("tackle", 0, "tera")

        val result = NativeObservedTurnActionMatcher.match(
            BattleFormat.DOUBLE,
            BattleSide.OPPONENT,
            frame(single = false),
            listOf(base, tera),
            emptyList(),
        )

        assertEquals(listOf(base.actionId), result.actions.map { it.actionId })
    }

    private fun move(
        moveId: String,
        slot: Int,
        mechanic: String? = null,
        target: BattleTargetSlot? = null,
    ) = BattleActionCandidate(
        actionId = "move-$moveId-$slot-${mechanic ?: "base"}" +
            target?.let { "-target-${it.side}-${it.slot}" }.orEmpty(),
        kind = BattleActionKind.USE_MOVE,
        actorSlot = slot,
        moveSlot = 0,
        moveId = moveId,
        targets = target?.let(::listOf).orEmpty(),
        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
    )

    private fun switch(id: UUID, slot: Int) = BattleActionCandidate(
        actionId = "switch-$id-$slot",
        kind = BattleActionKind.SWITCH,
        actorSlot = slot,
        switchPokemonId = id,
    )

    private fun joint(vararg components: BattleActionCandidate) = BattleActionCandidate(
        actionId = "joint-${components.joinToString("+") { it.actionId }}",
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = components.map { it.actionId },
        componentActions = components.toList(),
    )

    private fun event(
        sequence: Long,
        kind: BattleObservedEventKind,
        actor: UUID,
        value: String? = null,
        actorSlot: Int,
        targets: List<UUID> = emptyList(),
    ) = BattleObservedEventView(
        sequence = sequence,
        turn = 1,
        kind = kind,
        actorPokemonId = actor,
        targetPokemonIds = targets,
        publicValueId = value,
        actorSlot = actorSlot,
    )

    private fun frame(single: Boolean): NativeBattleFrame {
        val ally = pokemon(ALLY, 0, "tackle")
        val allyB = pokemon(ALLY_B, 1, "splash")
        val opponentA = pokemon(OPPONENT_A, 0, "growl", "tackle", "uturn", "flamethrower", "taunt")
        val opponentB = pokemon(OPPONENT_B, 1, "protect", "taunt")
        val bench = pokemon(BENCH, null, "tackle")
        return NativeBattleFrame(
            snapshotJson = "root",
            turn = 1,
            requestState = "move",
            ended = false,
            p1Active = if (single) listOf(ally) else listOf(ally, allyB),
            p2Active = if (single) listOf(opponentA) else listOf(opponentA, opponentB),
            p1Team = if (single) listOf(ally) else listOf(ally, allyB),
            p2Team = if (single) listOf(opponentA, bench) else listOf(opponentA, opponentB, bench),
            p1RequestJson = "null",
            p2RequestJson = "null",
            log = emptyList(),
        )
    }

    private fun pokemon(id: UUID, activeSlot: Int?, vararg moves: String) = NativePokemonFrame(
        uuid = id.toString(),
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
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val ALLY_B: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val OPPONENT_A: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val OPPONENT_B: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000000203")
    }
}
