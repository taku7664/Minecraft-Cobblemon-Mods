package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchTerminationReason
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeRecursiveSearchTest {
    @Test
    fun `root maximizes the worst native opponent response`() {
        val root = frame(
            snapshot = "root",
            turn = 1,
            allyHp = 100,
            opponentHp = 100,
            allyMoves = listOf("tackle", "scratch"),
            opponentMoves = listOf("growl", "tailwhip"),
        )
        val worker = RecordingWorker(
            mapOf(
                BranchKey("root", "move 1", "move 1") to terminal("a1-o1", 90, 40),
                BranchKey("root", "move 1", "move 2") to terminal("a1-o2", 40, 40),
                BranchKey("root", "move 2", "move 1") to terminal("a2-o1", 80, 50),
                BranchKey("root", "move 2", "move 2") to terminal("a2-o2", 70, 50),
            ),
        )
        val tree = NativeShowdownSearchTree(worker, root, template())

        val result = NativeRecursiveSearch(
            tree = tree,
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 1)

        assertEquals(1, result.depthCompleted)
        assertEquals(false, result.truncated)
        assertEquals("scratch", result.bestAction?.moveId)
        assertEquals(
            mapOf("tackle" to 0.0, "scratch" to 0.2),
            result.rootValues.associate { it.action.moveId to rounded(it.value) },
        )
        assertEquals(4, result.nodesVisited)
    }

    @Test
    fun `completed depth two recursively maximizes the worst native response`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl"))
        val child = frame(
            "child",
            2,
            80,
            60,
            listOf("scratch", "quickattack"),
            listOf("tailwhip", "leer"),
        )
        val worker = RecordingWorker(
            mapOf(
                BranchKey("root", "move 1", "move 1") to child,
                BranchKey("child", "move 1", "move 1") to terminal("c-a1-o1", 90, 40),
                BranchKey("child", "move 1", "move 2") to terminal("c-a1-o2", 40, 40),
                BranchKey("child", "move 2", "move 1") to terminal("c-a2-o1", 80, 50),
                BranchKey("child", "move 2", "move 2") to terminal("c-a2-o2", 70, 50),
            ),
        )

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 2)

        assertEquals(2, result.depthCompleted)
        assertEquals(false, result.truncated)
        assertEquals(0.2, rounded(result.rootValues.single().value))
        assertEquals(listOf("root", "root", "child", "child", "child", "child"), worker.visitedSnapshots)
    }

    @Test
    fun `interrupted deeper iteration keeps the last fully completed depth`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl"))
        val child = frame("child", 2, 80, 60, listOf("scratch"), listOf("tailwhip"))
        val worker = RecordingWorker(
            mapOf(
                BranchKey("root", "move 1", "move 1") to child,
                BranchKey("child", "move 1", "move 1") to terminal("grandchild", 60, 20),
            ),
        )
        var continuationChecks = 0
        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
            shouldContinue = { ++continuationChecks < 6 },
        ).evaluate(maxDepth = 2)

        assertEquals(1, result.depthCompleted)
        assertEquals(true, result.truncated)
        assertEquals(NativeSearchTerminationReason.DEADLINE, result.terminationReason)
        assertEquals(0.2, rounded(result.rootValues.single().value))
        assertEquals(listOf("root", "root", "child"), worker.visitedSnapshots)
    }

    @Test
    fun `node budget also discards a partial deeper iteration`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl"))
        val child = frame("child", 2, 80, 60, listOf("scratch"), listOf("tailwhip"))
        val worker = RecordingWorker(
            mapOf(
                BranchKey("root", "move 1", "move 1") to child,
                BranchKey("child", "move 1", "move 1") to terminal("grandchild", 60, 20),
            ),
        )
        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 2,
        ).evaluate(maxDepth = 2)

        assertEquals(1, result.depthCompleted)
        assertEquals(true, result.truncated)
        assertEquals(NativeSearchTerminationReason.NODE_BUDGET, result.terminationReason)
        assertEquals(0.2, rounded(result.rootValues.single().value))
        assertEquals(listOf("root", "root"), worker.visitedSnapshots)
    }

    @Test
    fun `product root actions are mapped before search and restored in the result`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl"))
        val worker = RecordingWorker(
            mapOf(BranchKey("root", "move 1", "move 1") to terminal("after", 80, 60)),
        )
        val productAction = BattleActionCandidate(
            actionId = "product:tackle",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:Tackle",
            targets = listOf(jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot(BattleSide.OPPONENT, 0)),
        )

        val attempt = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluateProduct(listOf(productAction), maxDepth = 1)

        assertEquals(true, attempt.mapping.complete)
        assertEquals(productAction.actionId, attempt.result?.rootValues?.single()?.action?.actionId)
        assertEquals(listOf("root"), worker.visitedSnapshots)
    }

    @Test
    fun `incomplete product root mapping never executes a native branch`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl"))
        val worker = RecordingWorker(emptyMap())
        val unavailableAction = BattleActionCandidate(
            actionId = "product:powergem",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "powergem",
        )

        val attempt = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluateProduct(listOf(unavailableAction), maxDepth = 1)

        assertEquals(false, attempt.mapping.complete)
        assertNull(attempt.result)
        assertEquals(emptyList<String>(), worker.visitedSnapshots)
    }

    private data class BranchKey(val snapshot: String, val p1Choice: String, val p2Choice: String)

    private class RecordingWorker(
        private val branches: Map<BranchKey, NativeBattleFrame>,
    ) : NativeBranchWorker {
        override val rulesFingerprint: String = "test-rules"
        val visitedSnapshots = mutableListOf<String>()

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame =
            error("This test starts from an existing native frame")

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            visitedSnapshots += snapshotJson
            return requireNotNull(branches[BranchKey(snapshotJson, p1Choice, p2Choice)])
        }

        override fun close() = Unit
    }

    private fun terminal(snapshot: String, allyHp: Int, opponentHp: Int) = frame(
        snapshot = snapshot,
        turn = 2,
        allyHp = allyHp,
        opponentHp = opponentHp,
        allyMoves = listOf("tackle"),
        opponentMoves = listOf("growl"),
        ended = true,
    )

    private fun frame(
        snapshot: String,
        turn: Int,
        allyHp: Int,
        opponentHp: Int,
        allyMoves: List<String>,
        opponentMoves: List<String>,
        ended: Boolean = false,
    ): NativeBattleFrame {
        val ally = pokemon(ALLY, allyHp, allyMoves)
        val opponent = pokemon(OPPONENT, opponentHp, opponentMoves)
        return NativeBattleFrame(
            snapshotJson = snapshot,
            turn = turn,
            requestState = if (ended) "ended" else "move",
            ended = ended,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = moveRequest(allyMoves),
            p2RequestJson = moveRequest(opponentMoves),
            log = emptyList(),
        )
    }

    private fun pokemon(uuid: UUID, hp: Int, moves: List<String>) = NativePokemonFrame(
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
        moves = moves.map { NativeMoveFrame(it, 35, 35, false) },
        activeSlot = 0,
        stats = STATS,
    )

    private fun moveRequest(moves: List<String>) =
        "{\"active\":[{\"moves\":[" + moves.joinToString(",") { move ->
            "{\"move\":\"$move\",\"id\":\"$move\",\"pp\":35,\"maxpp\":35," +
                "\"target\":\"normal\",\"disabled\":false}"
        } + "]}]}"

    private fun material(state: BattleStateView): Double {
        val ally = state.pokemon.single { it.side == BattleSide.ALLY }.hpFraction
        val opponent = state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction
        return ally - opponent
    }

    private fun rounded(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

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
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("psychic"),
    )

    private companion object {
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val STATS = mapOf("atk" to 100, "def" to 100, "spa" to 100, "spd" to 100, "spe" to 100)
    }
}
