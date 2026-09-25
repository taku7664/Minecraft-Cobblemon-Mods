package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSourceSetFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeProductSessionReconcilerTest {
    @Test
    fun `public opponent move advances the retained native root and clears the pending own action`() {
        val next = frame("next", turn = 2)
        val worker = Worker(mapOf("root" to next))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val event = moveEvent(1)
        val current = context(turn = 2, events = listOf(event))

        val result = reconciler.reconcile(session(listOf(world("world", "root", 1.0))), current, Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        assertEquals("next", result.sessionState?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
        assertEquals(2, result.sessionState?.publicTurn)
        assertEquals(1L, result.sessionState?.lastObservedEventSequence)
        assertNull(result.sessionState?.pendingOwnAction)
        assertEquals(1, worker.branchCalls)
        assertEquals("move 1" to "move 1", worker.choices.single())
    }

    @Test
    fun `random particle contradicting the public board is removed and posterior mass is renormalized`() {
        val matching = frame("matching", turn = 2)
        val mismatching = frame("mismatching", turn = 2, opponentHp = 99)
        val worker = Worker(mapOf("root-good" to matching, "root-bad" to mismatching))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val current = context(turn = 2, events = listOf(moveEvent(1)))
        val prior = session(listOf(
            world("good", "root-good", 0.5, sample = 0),
            world("bad", "root-bad", 0.5, sample = 1),
        ))

        val result = reconciler.reconcile(prior, current, Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        assertEquals(listOf("good"), result.sessionState?.worlds?.map { it.key.hypothesisId })
        assertEquals(1.0, result.sessionState?.worlds?.single()?.probability)
        assertEquals(2, worker.branchCalls)
    }

    @Test
    fun `unobserved opponent command keeps distinct compatible descendants with unique lineage`() {
        val opponentMoves = listOf("growl", "protect")
        val root = frame("root", turn = 1, opponentMoves = opponentMoves)
        val first = frame("first", turn = 2, opponentMoves = opponentMoves)
        val second = frame("second", turn = 2, opponentMoves = opponentMoves)
        val worker = Worker(mapOf("root|move 1" to first, "root|move 2" to second))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(world(
            "world",
            "root",
            1.0,
            definition = definition(opponentMoves),
            rootFrame = root,
        )))

        val result = reconciler.reconcile(prior, context(turn = 2), Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        val worlds = requireNotNull(result.sessionState).worlds
        assertEquals(setOf("first", "second"), worlds.map { it.rootSnapshot.frame.snapshotJson }.toSet())
        assertEquals(listOf(0.5, 0.5), worlds.map { it.probability })
        assertEquals(2, worlds.map { it.key }.distinct().size)
        assertTrue(worlds.all { it.key.lineage.isNotBlank() })
        assertEquals(2, worker.branchCalls)
    }

    @Test
    fun `submitted opponent move is deferred while the next native request waits`() {
        val next = frame("waiting", turn = 2, p2RequestJson = WAIT_REQUEST)
        val worker = Worker(mapOf("root" to next))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }

        val result = reconciler.reconcile(
            session(listOf(world("world", "root", 1.0))),
            context(turn = 2),
            Long.MAX_VALUE,
        )

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        val retained = requireNotNull(result.sessionState).worlds.single()
        assertNull(retained.deferredAllyAction)
        assertEquals("growl", retained.deferredOpponentAction?.moveId)
        assertEquals(BattleActionKind.USE_MOVE, retained.deferredOpponentAction?.kind)
    }

    @Test
    fun `delayed move evidence consumes the deferred command before matching current wait`() {
        val waiting = frame("waiting", turn = 1, p2RequestJson = WAIT_REQUEST)
        val next = frame("next", turn = 2, p2RequestJson = WAIT_REQUEST)
        val worker = Worker(mapOf("waiting" to next))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(world(
            "world",
            "waiting",
            1.0,
            rootFrame = waiting,
            deferredOpponentAction = opponentMove("growl"),
        )))

        val result = reconciler.reconcile(
            prior,
            context(turn = 2, events = listOf(moveEvent(1, "growl"))),
            Long.MAX_VALUE,
        )

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.observedActionIssues.toString())
        assertEquals("move 1" to "pass", worker.choices.single())
        assertNull(result.sessionState?.worlds?.single()?.deferredOpponentAction)
    }

    @Test
    fun `mismatched delayed move evidence eliminates the deferred world`() {
        val waiting = frame("waiting", turn = 1, p2RequestJson = WAIT_REQUEST)
        val worker = Worker(emptyMap())
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(world(
            "world",
            "waiting",
            1.0,
            rootFrame = waiting,
            deferredOpponentAction = opponentMove("growl"),
        )))

        val result = reconciler.reconcile(
            prior,
            context(turn = 2, events = listOf(moveEvent(1, "protect"))),
            Long.MAX_VALUE,
        )

        assertEquals(NativeProductSessionReconcileStatus.OBSERVED_ACTION_MISMATCH, result.status)
        assertEquals("world", result.failedWorldId)
        assertEquals(0, worker.branchCalls)
    }

    @Test
    fun `identical snapshots with different deferred commands remain separate worlds`() {
        val opponentMoves = listOf("growl", "protect")
        val root = frame("root", turn = 1, opponentMoves = opponentMoves)
        val waiting = frame(
            "same-waiting-snapshot",
            turn = 2,
            opponentMoves = opponentMoves,
            p2RequestJson = WAIT_REQUEST,
        )
        val worker = Worker(mapOf(
            "root|move 1" to waiting,
            "root|move 2" to waiting,
        ))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(world(
            "world",
            "root",
            1.0,
            definition = definition(opponentMoves),
            rootFrame = root,
        )))

        val result = reconciler.reconcile(prior, context(turn = 2), Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        val worlds = requireNotNull(result.sessionState).worlds
        assertEquals(2, worlds.size)
        assertEquals(setOf("growl", "protect"), worlds.mapNotNull { it.deferredOpponentAction?.moveId }.toSet())
        assertEquals(setOf("same-waiting-snapshot"), worlds.map { it.rootSnapshot.frame.snapshotJson }.toSet())
        assertEquals(listOf(0.5, 0.5), worlds.map { it.probability })
        assertEquals(2, worlds.map { it.key.lineage }.distinct().size)
    }

    @Test
    fun `bounded public event history gap fails before leasing a native worker`() {
        var leased = false
        val reconciler = NativeProductSessionReconciler { _, _ -> leased = true; error("must not lease") }
        val prior = session(listOf(world("world", "root", 1.0)), lastSequence = 3L)
        val current = context(turn = 2, events = listOf(moveEvent(5)))

        val result = reconciler.reconcile(prior, current, Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.PUBLIC_EVENT_HISTORY_GAP, result.status)
        assertNull(result.sessionState)
        assertFalse(leased)
    }

    @Test
    fun `rules generation mismatch rejects every persisted snapshot before branching`() {
        val worker = Worker(mapOf("root" to frame("next", turn = 2)), rules = "new-rules")
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }

        val result = reconciler.reconcile(
            session(listOf(world("world", "root", 1.0))),
            context(turn = 2, events = listOf(moveEvent(1))),
            Long.MAX_VALUE,
        )

        assertEquals(NativeProductSessionReconcileStatus.RULES_GENERATION_MISMATCH, result.status)
        assertEquals(0, worker.branchCalls)
    }

    private class Worker(
        private val results: Map<String, NativeBattleFrame>,
        private val rules: String = RULES,
    ) : NativeBranchWorker {
        override val rulesFingerprint: String = rules
        var branchCalls = 0
        val choices = mutableListOf<Pair<String, String>>()

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame = error("must reuse root")

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            branchCalls++
            choices += p1Choice to p2Choice
            return requireNotNull(results["$snapshotJson|$p2Choice"] ?: results[snapshotJson])
        }

        override fun close() = Unit
    }

    private fun session(
        worlds: List<NativeProductSessionWorld>,
        lastSequence: Long? = null,
    ) = NativeProductSessionState(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        rulesFingerprint = RULES,
        worlds = worlds,
        publicTurn = 1,
        lastObservedEventSequence = lastSequence,
        pendingOwnAction = PRODUCT_TACKLE,
    )

    private fun world(
        id: String,
        snapshot: String,
        probability: Double,
        sample: Int = 0,
        definition: NativeBattleDefinition = DEFINITION,
        rootFrame: NativeBattleFrame = frame(snapshot, turn = 1),
        deferredAllyAction: BattleActionCandidate? = null,
        deferredOpponentAction: BattleActionCandidate? = null,
    ) = NativeProductSessionWorld(
        key = NativeSearchWorldKey(id, sample),
        probability = probability,
        definition = definition,
        rootSnapshot = NativeProductRootSnapshot(RULES, rootFrame),
        publicContext = context(turn = 1),
        deferredAllyAction = deferredAllyAction,
        deferredOpponentAction = deferredOpponentAction,
    )

    private fun context(
        turn: Int,
        events: List<BattleObservedEventView> = emptyList(),
    ) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("request-$turn".toByteArray()),
        state = state(turn, events),
        candidates = listOf(PRODUCT_TACKLE),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun state(turn: Int, events: List<BattleObservedEventView>) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(
            publicPokemon(ALLY, BattleSide.ALLY),
            publicPokemon(OPPONENT, BattleSide.OPPONENT),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun publicPokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        id, side, 0, "cobblemon:mew", null, 50, 1.0, null,
        emptyMap(), emptySet(), null, null, false, setOf("psychic"),
    )

    private fun moveEvent(sequence: Long, moveId: String = "growl") = BattleObservedEventView(
        sequence = sequence,
        turn = 1,
        kind = BattleObservedEventKind.MOVE_USED,
        actorPokemonId = OPPONENT,
        publicValueId = moveId,
        actorSlot = 0,
    )

    private fun frame(
        snapshot: String,
        turn: Int,
        opponentHp: Int = 100,
        opponentMoves: List<String> = listOf("growl"),
        p1RequestJson: String = moveRequest(listOf("tackle")),
        p2RequestJson: String = moveRequest(opponentMoves),
    ): NativeBattleFrame {
        val ally = nativePokemon(ALLY, listOf("tackle"), 100)
        val opponent = nativePokemon(OPPONENT, opponentMoves, opponentHp)
        return NativeBattleFrame(
            snapshotJson = snapshot,
            turn = turn,
            requestState = "move",
            ended = false,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = p1RequestJson,
            p2RequestJson = p2RequestJson,
            log = emptyList(),
        )
    }

    private fun nativePokemon(id: UUID, moves: List<String>, hp: Int) = NativePokemonFrame(
        uuid = id.toString(),
        species = "mew",
        hp = hp,
        maxHp = 100,
        status = "",
        ability = "synchronize",
        item = "",
        types = listOf("Psychic"),
        boosts = emptyMap(),
        volatiles = emptyList(),
        moves = moves.map { NativeMoveFrame(it, 10, 10, false) },
        activeSlot = 0,
        level = 50,
        stats = BATTLE_STATS,
        sourceSet = NativePokemonSourceSetFrame(
            species = "mew",
            ability = "synchronize",
            item = "",
            moves = moves,
            nature = "Serious",
            gender = "N",
            evs = ZERO_EVS,
            ivs = PERFECT_IVS,
        ),
    )

    private fun moveRequest(moves: List<String>) = moves.joinToString(
        prefix = "{\"active\":[{\"moves\":[",
        postfix = "]}]}",
    ) { move ->
        "{\"move\":\"$move\",\"id\":\"$move\",\"pp\":10," +
            "\"maxpp\":10,\"target\":\"normal\",\"disabled\":false}"
    }

    private fun opponentMove(moveId: String) = BattleActionCandidate(
        actionId = "native:opponent:slot:0:move:0:$moveId::base",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = moveId,
    )

    private fun definition(opponentMoves: List<String>) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(1, 2, 3, 4),
        p1Team = listOf(NativePokemonSet(
            "ally", "mew", listOf("tackle"), "synchronize", uuid = ALLY.toString(),
            nature = "Serious", gender = "N", evs = ZERO_EVS, ivs = PERFECT_IVS,
        )),
        p2Team = listOf(NativePokemonSet(
            "opponent", "mew", opponentMoves, "synchronize", uuid = OPPONENT.toString(),
            nature = "Serious", gender = "N", evs = ZERO_EVS, ivs = PERFECT_IVS,
        )),
    )

    private companion object {
        const val RULES = "rules-v1"
        const val WAIT_REQUEST = "{\"wait\":true}"
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = ZERO_EVS.mapValues { 31 }
        val BATTLE_STATS = mapOf("atk" to 100, "def" to 100, "spa" to 100, "spd" to 100, "spe" to 100)
        val PRODUCT_TACKLE = BattleActionCandidate(
            actionId = "product-tackle",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "tackle",
        )
        val DEFINITION = NativeBattleDefinition(
            formatId = "cobblemonsingles",
            seed = listOf(1, 2, 3, 4),
            p1Team = listOf(NativePokemonSet(
                "ally", "mew", listOf("tackle"), "synchronize", uuid = ALLY.toString(),
                nature = "Serious", gender = "N", evs = ZERO_EVS, ivs = PERFECT_IVS,
            )),
            p2Team = listOf(NativePokemonSet(
                "opponent", "mew", listOf("growl"), "synchronize", uuid = OPPONENT.toString(),
                nature = "Serious", gender = "N", evs = ZERO_EVS, ivs = PERFECT_IVS,
            )),
        )
    }
}
