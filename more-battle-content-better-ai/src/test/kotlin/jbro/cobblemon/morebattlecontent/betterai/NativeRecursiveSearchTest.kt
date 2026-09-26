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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeRecursiveSearchTest {
    @Test
    fun `native recoil charges fifty points per full hp bar`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "doubleedge"), listOf("splash"))
        val worker = RecordingWorker(mapOf(
            BranchKey("root", "move 1", "move 1") to terminal("plain", 100, 70),
            BranchKey("root", "move 2", "move 1") to terminal("recoil", 80, 70).copy(recoilLossP1 = 0.2),
        ))
        val values = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("recoil", 0),
            evaluate = ::material,
            nodeLimit = 10,
        ).evaluate(1).rootValues.associate { it.action.moveId to it.value }

        assertEquals(0.3, values.getValue("tackle"), 1e-9)
        assertEquals(0.2, values.getValue("doubleedge"), 1e-9)
    }

    @Test
    fun `deeper native tempo does not charge recoil a second time`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "doubleedge"), listOf("splash"))
        val plain = frame("plain", 2, 100, 70, listOf("splash"), listOf("splash"))
        val recoil = frame("recoil", 2, 80, 70, listOf("splash"), listOf("splash"))
            .copy(recoilLossP1 = 0.2)
        val worker = RecordingWorker(mapOf(
            BranchKey("root", "move 1", "move 1") to plain,
            BranchKey("root", "move 2", "move 1") to recoil,
            BranchKey("plain", "move 1", "move 1") to terminal("plain-end", 100, 70),
            BranchKey("recoil", "move 1", "move 1") to terminal("recoil-end", 80, 70),
        ))
        val values = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("recoil-depth-two", 0),
            evaluate = ::material,
            nodeLimit = 10,
        ).evaluate(2).rootValues.associate { it.action.moveId to it.value }

        assertEquals(0.1, values.getValue("tackle") - values.getValue("doubleedge"), 1e-9)
    }

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
        assertEquals(0.35, result.rootValues.single().value, 1e-9)
        assertEquals(listOf(1, 2), result.completedIterations.map { it.depth })
        assertEquals(0.2, rounded(result.completedIterations.first().rootValues.single().value))
        assertEquals(5, result.nodesVisited)
        assertEquals(listOf("root", "child", "child", "child", "child"), worker.visitedSnapshots,
            "Iterative deepening must reuse the depth-independent root transition")
    }

    @Test
    fun `same final board prefers damage achieved on the first turn`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "scratch"), listOf("growl"))
        val early = frame("early", 2, 100, 50, listOf("quickattack"), listOf("tailwhip"))
        val late = frame("late", 2, 100, 100, listOf("quickattack"), listOf("tailwhip"))
        val worker = RecordingWorker(mapOf(
            BranchKey("root", "move 1", "move 1") to early,
            BranchKey("root", "move 2", "move 1") to late,
            BranchKey("early", "move 1", "move 1") to terminal("early-finish", 100, 0),
            BranchKey("late", "move 1", "move 1") to terminal("late-finish", 100, 0),
        ))

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("tempo", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 2)

        assertEquals(2, result.depthCompleted)
        assertEquals("tackle", result.bestAction?.moveId)
        val values = result.rootValues.associate { it.action.moveId to it.value }
        assertTrue(values.getValue("tackle") > values.getValue("scratch"),
            "The same final board must not erase the first turn's progress")
    }

    @Test
    fun `bounded child search keeps exact root value when previous depth reorders replies`() {
        val root = frame("root", 1, 100, 100, listOf("tackle"), listOf("growl", "tailwhip"))
        val first = frame("first", 2, 90, 60, listOf("scratch", "quickattack"), listOf("leer", "growl"))
        val second = frame("second", 2, 80, 60, listOf("scratch", "quickattack"), listOf("leer", "growl"))
        val worker = RecordingWorker(mapOf(
            BranchKey("root", "move 1", "move 1") to first,
            BranchKey("root", "move 1", "move 2") to second,
            BranchKey("first", "move 1", "move 1") to terminal("f-1-1", 100, 50),
            BranchKey("first", "move 1", "move 2") to terminal("f-1-2", 100, 50),
            BranchKey("first", "move 2", "move 1") to terminal("f-2-1", 90, 50),
            BranchKey("first", "move 2", "move 2") to terminal("f-2-2", 90, 50),
            BranchKey("second", "move 1", "move 1") to terminal("s-1-1", 100, 20),
            BranchKey("second", "move 1", "move 2") to terminal("s-1-2", 100, 30),
            BranchKey("second", "move 2", "move 1") to terminal("s-2-1", 100, 10),
            BranchKey("second", "move 2", "move 2") to terminal("s-2-2", 100, 10),
        ))

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("bounded-child", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 2)

        assertEquals(2, result.depthCompleted)
        assertEquals(false, result.truncated)
        assertEquals(0.705, result.rootValues.single().value, 1e-9)
        assertEquals(10, result.nodesVisited, "Prior-depth ordering may visit more nodes on this counterexample")
        assertEquals(4, worker.visitedSnapshots.count { it == "second" })
    }

    @Test
    fun `a pruned bound is not cached as another product root action exact value`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "scratch"), listOf("growl", "tailwhip"))
        fun child(id: String) = frame(id, 2, 90, 60,
            listOf("quickattack", "bodyslam"), listOf("leer", "growl"))
        val first = child("first")
        val shared = child("shared")
        val last = child("last")
        val branches = mutableMapOf(
            BranchKey("root", "move 1", "move 1") to first,
            BranchKey("root", "move 1", "move 2") to shared,
            BranchKey("root", "move 2", "move 1") to shared,
            BranchKey("root", "move 2", "move 2") to last,
        )
        fun fill(id: String, firstValue: Int, secondValue: Int) {
            for (opponent in 1..2) {
                branches[BranchKey(id, "move 1", "move $opponent")] =
                    terminal("$id-1-$opponent", 100, 100 - firstValue)
                branches[BranchKey(id, "move 2", "move $opponent")] =
                    terminal("$id-2-$opponent", 100, 100 - secondValue)
            }
        }
        fill("first", 50, 40)
        fill("shared", 70, 90)
        fill("last", 80, 70)
        val worker = RecordingWorker(branches)

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("root-bound-cache", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 2)

        assertEquals(2, result.depthCompleted)
        assertEquals(false, result.truncated)
        val rootValues = result.rootValues.associate { it.action.moveId to it.value }
        assertEquals(0.705, rootValues.getValue("tackle"), 1e-9)
        assertEquals(0.975, rootValues.getValue("scratch"), 1e-9)
        assertEquals("scratch", result.bestAction?.moveId)
        assertEquals(4, worker.visitedSnapshots.count { it == "shared" },
            "A later root candidate must finish the previously pruned shared state")
    }

    @Test
    fun `transposed native state reuses its value at the same remaining depth`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "scratch"), listOf("growl"))
        val shared = frame("shared", 2, 80, 60, listOf("quickattack"), listOf("tailwhip"))
        val worker = RecordingWorker(
            mapOf(
                BranchKey("root", "move 1", "move 1") to shared,
                BranchKey("root", "move 2", "move 1") to shared,
                BranchKey("shared", "move 1", "move 1") to terminal("terminal", 70, 40),
            ),
        )

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("hypothesis-1", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 100,
        ).evaluate(maxDepth = 2)

        assertEquals(2, result.depthCompleted)
        assertEquals(3, result.nodesVisited)
        assertEquals(listOf("root", "root", "shared"), worker.visitedSnapshots,
            "The converged position must expand only once at the same remaining depth")
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
        assertEquals(listOf(1), result.completedIterations.map { it.depth })
        assertEquals(listOf("root", "child"), worker.visitedSnapshots)
    }

    @Test
    fun `cached transitions do not consume the node budget again`() {
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

        assertEquals(2, result.depthCompleted)
        assertEquals(false, result.truncated)
        assertEquals(NativeSearchTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(0.53, result.rootValues.single().value, 1e-9)
        assertEquals(2, result.nodesVisited)
        assertEquals(listOf("root", "child"), worker.visitedSnapshots)
    }

    @Test
    fun `evicted native snapshots are replayed only within the deterministic node budget`() {
        val root = frame("root", 1, 100, 100, listOf("tackle", "scratch"), listOf("growl"))
        val worker = RecordingWorker(mapOf(
            BranchKey("root", "move 1", "move 1") to terminal("first", 80, 60),
            BranchKey("root", "move 2", "move 1") to terminal("second", 70, 50),
        ))

        val result = NativeRecursiveSearch(
            tree = NativeShowdownSearchTree(worker, root, template()),
            world = NativeSearchWorldKey("bounded-cache", randomSampleIndex = 0),
            evaluate = ::material,
            nodeLimit = 3,
            cacheEntryLimit = 1,
        ).evaluate(maxDepth = 2)

        assertEquals(1, result.depthCompleted)
        assertEquals(true, result.truncated)
        assertEquals(NativeSearchTerminationReason.NODE_BUDGET, result.terminationReason)
        assertEquals(3, result.nodesVisited)
        assertEquals(listOf("root", "root", "root"), worker.visitedSnapshots)
    }

    @Test
    fun `node budget still stops the first uncached deeper transition`() {
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
            nodeLimit = 1,
        ).evaluate(maxDepth = 2)

        assertEquals(1, result.depthCompleted)
        assertEquals(true, result.truncated)
        assertEquals(NativeSearchTerminationReason.NODE_BUDGET, result.terminationReason)
        assertEquals(1, result.nodesVisited)
        assertEquals(listOf("root"), worker.visitedSnapshots)
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

        override fun rebindMoves(
            snapshotJson: String,
            rebindings: List<jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding>,
        ): NativeBattleFrame = error("recursive search must not rebind move hypotheses")

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
