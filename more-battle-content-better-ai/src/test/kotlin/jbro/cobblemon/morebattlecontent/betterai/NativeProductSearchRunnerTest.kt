package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchTerminationReason
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSourceSetFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeProductSearchRunnerTest {
    @Test
    fun `runner leases one worker creates the root and searches product actions`() {
        val worker = Worker(rootFrame(), terminalFrame())
        val runner = NativeProductSearchRunner { _, action -> action(worker) }

        val run = runner.run(request(productMove("tackle")))

        assertEquals(NativeProductSearchRunStatus.COMPLETED, run.status)
        assertEquals(1, worker.createCalls)
        assertEquals(1, worker.branchCalls)
        assertEquals("product:tackle", run.result?.rootValues?.single()?.action?.actionId)
        assertEquals("test-rules", run.rootSnapshot?.rulesFingerprint)
        assertEquals("root", run.rootSnapshot?.frame?.snapshotJson)
    }

    @Test
    fun `runner searches a persisted root without recreating the battle`() {
        val worker = Worker(rootFrame(), terminalFrame())
        val runner = NativeProductSearchRunner { _, action -> action(worker) }
        val persisted = NativeProductRootSnapshot("test-rules", rootFrame())

        val run = runner.run(request(productMove("tackle")).copy(rootSnapshot = persisted))

        assertEquals(NativeProductSearchRunStatus.COMPLETED, run.status)
        assertEquals(0, worker.createCalls)
        assertEquals(1, worker.branchCalls)
        assertEquals(persisted, run.rootSnapshot)
    }

    @Test
    fun `persisted root from another rules generation fails before native execution`() {
        val worker = Worker(rootFrame(), terminalFrame())
        val runner = NativeProductSearchRunner { _, action -> action(worker) }
        val stale = NativeProductRootSnapshot("stale-rules", rootFrame())

        val run = runner.run(request(productMove("tackle")).copy(rootSnapshot = stale))

        assertEquals(NativeProductSearchRunStatus.RULES_GENERATION_MISMATCH, run.status)
        assertEquals(0, worker.createCalls)
        assertEquals(0, worker.branchCalls)
        assertNull(run.result)
    }

    @Test
    fun `missing runtime is explicit and does not pretend a search ran`() {
        val runner = NativeProductSearchRunner { _, _ -> null }

        val run = runner.run(request(productMove("tackle")))

        assertEquals(NativeProductSearchRunStatus.RUNTIME_UNAVAILABLE, run.status)
        assertNull(run.result)
        assertNull(run.failure)
    }

    @Test
    fun `expired deadline does not lease a worker`() {
        var leaseCalls = 0
        val runner = NativeProductSearchRunner(
            nanoTime = { 100L },
            lease = { _, _ ->
                leaseCalls++
                null
            },
        )

        val run = runner.run(request(productMove("tackle")).copy(deadlineNanos = 100L))

        assertEquals(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED, run.status)
        assertEquals(0, leaseCalls)
        assertNull(run.result)
    }

    @Test
    fun `lease timeout is distinguished from an unavailable runtime`() {
        var now = 99L
        val runner = NativeProductSearchRunner(
            nanoTime = { now },
            lease = { _, _ ->
                now = 100L
                null
            },
        )

        val run = runner.run(request(productMove("tackle")).copy(deadlineNanos = 100L))

        assertEquals(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED, run.status)
        assertNull(run.result)
    }

    @Test
    fun `deadline reached during search is explicit and preserves the completed result`() {
        val worker = Worker(rootFrame(), terminalFrame())
        var now = 99L
        val runner = NativeProductSearchRunner(
            nanoTime = { now.also { now = 100L } },
            lease = { _, action -> action(worker) },
        )

        val run = runner.run(request(productMove("tackle")).copy(deadlineNanos = 100L))

        assertEquals(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED, run.status)
        assertEquals(NativeSearchTerminationReason.DEADLINE, run.result?.terminationReason)
        assertEquals(0, run.result?.depthCompleted)
        assertEquals(0, worker.branchCalls)
    }

    @Test
    fun `incomplete root mapping returns unavailable without a native branch`() {
        val worker = Worker(rootFrame(), terminalFrame())
        val runner = NativeProductSearchRunner { _, action -> action(worker) }

        val run = runner.run(request(productMove("powergem")))

        assertEquals(NativeProductSearchRunStatus.ROOT_ACTION_MAPPING_INCOMPLETE, run.status)
        assertEquals(1, worker.createCalls)
        assertEquals(0, worker.branchCalls)
        assertEquals(setOf("product:powergem"), run.mapping?.unmatchedProductActionIds)
    }

    @Test
    fun `native execution failure is returned instead of using a handmade transition`() {
        val worker = Worker(rootFrame(), terminalFrame(), createFailure = IllegalStateException("native failed"))
        val runner = NativeProductSearchRunner { _, action -> action(worker) }

        val run = runner.run(request(productMove("tackle")))

        assertEquals(NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE, run.status)
        assertNotNull(run.failure)
        assertEquals("native failed", run.failure?.message)
        assertEquals(0, worker.branchCalls)
    }

    @Test
    fun `native branch failure is returned instead of using a handmade transition`() {
        val worker = Worker(rootFrame(), terminalFrame(), branchFailure = IllegalStateException("branch failed"))
        val runner = NativeProductSearchRunner { _, action -> action(worker) }

        val run = runner.run(request(productMove("tackle")))

        assertEquals(NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE, run.status)
        assertEquals("branch failed", run.failure?.message)
        assertEquals(1, worker.createCalls)
        assertEquals(1, worker.branchCalls)
        assertNull(run.result)
    }

    @Test
    fun `inconsistent native root is explicit and never branches`() {
        val worker = Worker(rootFrame(), terminalFrame())
        val runner = NativeProductSearchRunner { _, action -> action(worker) }
        val inconsistent = request(productMove("tackle")).copy(
            publicState = template(allyStats = BattleCombatStatRangesView.exact(100, 101, 100, 100, 100, 100)),
        )

        val run = runner.run(inconsistent)

        assertEquals(NativeProductSearchRunStatus.ROOT_STATE_INCONSISTENT, run.status)
        assertEquals(setOf("COMBAT_STATS_MISMATCH"), run.rootIssues.mapTo(linkedSetOf()) { it.code.name })
        assertEquals(1, worker.createCalls)
        assertEquals(0, worker.branchCalls)
        assertNull(run.result)
    }

    private class Worker(
        private val root: NativeBattleFrame,
        private val terminal: NativeBattleFrame,
        private val createFailure: RuntimeException? = null,
        private val branchFailure: RuntimeException? = null,
    ) : NativeBranchWorker {
        override val rulesFingerprint: String = "test-rules"
        var createCalls = 0
        var branchCalls = 0

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame {
            createCalls++
            createFailure?.let { throw it }
            return root
        }

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            branchCalls++
            branchFailure?.let { throw it }
            assertEquals("root", snapshotJson)
            assertEquals("move 1", p1Choice)
            assertEquals("move 1", p2Choice)
            return terminal
        }

        override fun close() = Unit
    }

    private fun request(action: BattleActionCandidate) = jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRequest(
        definition = definition(),
        publicState = template(),
        productActions = listOf(action),
        world = NativeSearchWorldKey("hypothesis-1", 0),
        maxDepth = 1,
        nodeLimit = 100,
        deadlineNanos = Long.MAX_VALUE,
        evaluate = ::material,
    )

    private fun productMove(moveId: String) = BattleActionCandidate(
        actionId = "product:$moveId",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = moveId,
    )

    private fun definition() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(1, 2, 3, 4),
        p1Team = listOf(nativeSet("Ally", ALLY, "tackle")),
        p2Team = listOf(nativeSet("Opponent", OPPONENT, "growl")),
    )

    private fun nativeSet(name: String, uuid: UUID, move: String) = NativePokemonSet(
        name = name,
        species = "Mew",
        moves = listOf(move),
        ability = "Synchronize",
        uuid = uuid.toString(),
    )

    private fun rootFrame() = frame("root", false, 100, 100)

    private fun terminalFrame() = frame("terminal", true, 80, 60)

    private fun frame(snapshot: String, ended: Boolean, allyHp: Int, opponentHp: Int): NativeBattleFrame {
        val ally = pokemon(ALLY, allyHp, "tackle")
        val opponent = pokemon(OPPONENT, opponentHp, "growl")
        return NativeBattleFrame(
            snapshotJson = snapshot,
            turn = if (ended) 2 else 1,
            requestState = if (ended) "ended" else "move",
            ended = ended,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = moveRequest("tackle"),
            p2RequestJson = moveRequest("growl"),
            log = emptyList(),
        )
    }

    private fun pokemon(uuid: UUID, hp: Int, move: String) = NativePokemonFrame(
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
        moves = listOf(NativeMoveFrame(move, 35, 35, false)),
        activeSlot = 0,
        stats = STATS,
        sourceSet = NativePokemonSourceSetFrame(
            "Mew",
            "Synchronize",
            "",
            listOf(move),
            "Serious",
            "M",
            ZERO_EVS,
            PERFECT_IVS,
        ),
    )

    private fun moveRequest(move: String) =
        "{\"active\":[{\"moves\":[{\"move\":\"$move\",\"id\":\"$move\",\"pp\":35," +
            "\"maxpp\":35,\"target\":\"normal\",\"disabled\":false}]}]}"

    private fun material(state: BattleStateView): Double =
        state.pokemon.single { it.side == BattleSide.ALLY }.hpFraction -
            state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction

    private fun template(allyStats: BattleCombatStatRangesView? = null) = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            templatePokemon(ALLY, BattleSide.ALLY, allyStats),
            templatePokemon(OPPONENT, BattleSide.OPPONENT, null),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun templatePokemon(
        uuid: UUID,
        side: BattleSide,
        stats: BattleCombatStatRangesView?,
    ) = BattlePokemonStateView(
        uuid,
        side,
        0,
        "cobblemon:mew",
        null,
        50,
        1.0,
        null,
        emptyMap(),
        emptySet(),
        null,
        null,
        false,
        setOf("psychic"),
        stats,
    )

    private companion object {
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val STATS = mapOf("atk" to 100, "def" to 100, "spa" to 100, "spd" to 100, "spe" to 100)
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 31 }
    }
}
