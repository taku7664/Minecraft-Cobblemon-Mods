package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeShowdownSearchTreeTest {
    @Test
    fun `each depth branches from its parent native snapshot`() {
        val worker = RecordingWorker(
            frames = mapOf(
                "snapshot-1" to frame("snapshot-2", turn = 2, opponentHp = 80, moveId = "psychic"),
                "snapshot-2" to frame("snapshot-3", turn = 3, opponentHp = 60, moveId = "swift"),
            ),
        )
        val tree = NativeShowdownSearchTree(
            worker,
            frame("snapshot-1", turn = 1, opponentHp = 100, moveId = "tackle"),
            template(),
        )

        val root = tree.root
        val rootAllyAction = tree.actions(root, BattleSide.ALLY).single()
        val rootOpponentAction = tree.actions(root, BattleSide.OPPONENT).single()
        val first = tree.branch(
            root,
            rootAllyAction,
            rootOpponentAction,
        )
        val firstAllyAction = tree.actions(first, BattleSide.ALLY).single()
        val firstOpponentAction = tree.actions(first, BattleSide.OPPONENT).single()
        val second = tree.branch(
            first,
            firstAllyAction,
            firstOpponentAction,
        )

        assertEquals(listOf("tackle", "psychic"), listOf(rootAllyAction.moveId, firstAllyAction.moveId))
        assertEquals(listOf("snapshot-1", "snapshot-2"), worker.snapshots)
        assertEquals(listOf("move 1" to "move 1", "move 1" to "move 1"), worker.choices)
        assertEquals("snapshot-3", second.frame.snapshotJson)
        assertEquals(3, second.state.turn)
        assertEquals(
            0.6,
            second.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction,
        )
    }

    private class RecordingWorker(
        private val frames: Map<String, NativeBattleFrame>,
    ) : NativeBranchWorker {
        override val rulesFingerprint: String = "test-rules"
        val snapshots = mutableListOf<String>()
        val choices = mutableListOf<Pair<String, String>>()

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame =
            error("This test starts from an existing native frame")

        override fun rebindMoves(
            snapshotJson: String,
            rebindings: List<jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding>,
        ): NativeBattleFrame = error("search tree must not rebind move hypotheses")

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            snapshots += snapshotJson
            choices += p1Choice to p2Choice
            return requireNotNull(frames[snapshotJson])
        }

        override fun close() = Unit
    }

    private fun frame(snapshot: String, turn: Int, opponentHp: Int, moveId: String): NativeBattleFrame {
        val ally = pokemon(ALLY, 100, moveId)
        val opponent = pokemon(OPPONENT, opponentHp, moveId)
        return NativeBattleFrame(
            snapshotJson = snapshot,
            turn = turn,
            requestState = "move",
            ended = false,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = moveRequest(moveId),
            p2RequestJson = moveRequest(moveId),
            log = emptyList(),
        )
    }

    private fun pokemon(uuid: UUID, hp: Int, moveId: String) = NativePokemonFrame(
        uuid = uuid.toString(),
        species = "mew",
        hp = hp,
        maxHp = 100,
        status = "",
        ability = "synchronize",
        item = "",
        types = listOf("Psychic"),
        boosts = emptyMap(),
        volatiles = emptyList(),
        moves = listOf(NativeMoveFrame(moveId, 35, 35, false)),
        activeSlot = 0,
        stats = STATS,
    )

    private fun template() = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            templatePokemon(ALLY, BattleSide.ALLY),
            templatePokemon(OPPONENT, BattleSide.OPPONENT),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun templatePokemon(uuid: UUID, side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = uuid,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:mew",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = setOf("cobblemon:tackle"),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("psychic"),
    )

    private fun moveRequest(moveId: String) =
        "{\"active\":[{\"moves\":[{\"move\":\"$moveId\",\"id\":\"$moveId\",\"pp\":35," +
            "\"maxpp\":35,\"target\":\"normal\",\"disabled\":false}]}]}"

    private companion object {
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val STATS = mapOf("atk" to 100, "def" to 100, "spa" to 100, "spd" to 100, "spe" to 100)
    }
}
