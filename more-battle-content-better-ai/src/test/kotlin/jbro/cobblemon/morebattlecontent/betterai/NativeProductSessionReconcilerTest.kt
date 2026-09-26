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
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageRollFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeExecutedMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeForcedDamageRoll
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
    fun `boss removes the native world that contradicts public move order`() {
        val publicEvents = actionOrderTurnEvents()
        val matching = frame(
            "matching",
            turn = 2,
            executedMoveOrder = moveOrder(OPPONENT, ALLY),
        )
        val contradicted = frame(
            "contradicted",
            turn = 2,
            executedMoveOrder = moveOrder(ALLY, OPPONENT),
        )
        val worker = Worker(mapOf("root-scarf" to matching, "root-no-scarf" to contradicted))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(
            world("scarf", "root-scarf", 0.4),
            world("no-scarf", "root-no-scarf", 0.6),
        ))

        val result = reconciler.reconcile(prior, context(turn = 2, events = publicEvents), Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status)
        assertEquals(listOf("scarf"), result.sessionState?.worlds?.map { it.key.hypothesisId })
        assertEquals(1.0, result.sessionState?.worlds?.single()?.probability)
    }

    @Test
    fun `introductory keeps both speed worlds despite the same public move order`() {
        val publicEvents = actionOrderTurnEvents()
        val worker = Worker(mapOf(
            "root-scarf" to frame("matching", 2, executedMoveOrder = moveOrder(OPPONENT, ALLY)),
            "root-no-scarf" to frame("contradicted", 2, executedMoveOrder = moveOrder(ALLY, OPPONENT)),
        ))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(
            worlds = listOf(
                world("scarf", "root-scarf", 0.4),
                world("no-scarf", "root-no-scarf", 0.6),
            ),
            tier = BattleTrainerTier.INTRODUCTORY,
        )

        val result = reconciler.reconcile(prior, context(turn = 2, events = publicEvents), Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status)
        assertEquals(setOf("scarf", "no-scarf"), result.sessionState?.worlds?.map { it.key.hypothesisId }?.toSet())
    }

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
    fun `public direct damage weights build worlds and reconciles sampled HP before reuse`() {
        val highSupport = List(4) { 25 } + List(12) { 30 }
        val lowSupport = listOf(25) + List(15) { 15 }
        val high = frame(
            "high-sampled",
            turn = 2,
            opponentHp = 70,
            executedDamageRolls = listOf(damageRoll(actual = 30, possible = highSupport)),
        )
        val low = frame(
            "low-sampled",
            turn = 2,
            opponentHp = 85,
            executedDamageRolls = listOf(damageRoll(actual = 15, possible = lowSupport)),
        )
        val worker = Worker(mapOf("root-high" to high, "root-low" to low))
        val reconciler = NativeProductSessionReconciler { _, action -> action(worker) }
        val prior = session(listOf(
            world("high", "root-high", 0.5, sample = 0),
            world("low", "root-low", 0.5, sample = 1),
        ))

        val result = reconciler.reconcile(
            prior,
            context(turn = 2, events = directDamageTurnEvents(), opponentHpFraction = 0.75),
            Long.MAX_VALUE,
        )

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        val worlds = requireNotNull(result.sessionState).worlds.associateBy { it.key.hypothesisId }
        assertEquals(0.8, requireNotNull(worlds["high"]).probability, 1e-12)
        assertEquals(0.2, requireNotNull(worlds["low"]).probability, 1e-12)
        assertTrue(worlds.values.all { world ->
            world.rootSnapshot.frame.p2Active.single().hp == 75 &&
                world.rootSnapshot.frame.p2Team.single().hp == 75
        })
        assertEquals(5, worker.forcedDamageCalls,
            "Every supported native roll must be replayed before identical HP snapshots are merged")
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
    fun `incompatible unobserved opponent commands still consume prior probability`() {
        val opponentMoves = listOf("growl", "protect")
        val rootA = frame("root-a", turn = 1, opponentMoves = opponentMoves)
        val rootB = frame("root-b", turn = 1, opponentMoves = opponentMoves)
        val worker = Worker(mapOf(
            "root-a|move 1" to frame("a-compatible", turn = 2, opponentMoves = opponentMoves),
            "root-a|move 2" to frame("a-incompatible", turn = 2, opponentHp = 99,
                opponentMoves = opponentMoves),
            "root-b|move 1" to frame("b-compatible-1", turn = 2, opponentMoves = opponentMoves),
            "root-b|move 2" to frame("b-compatible-2", turn = 2, opponentMoves = opponentMoves),
        ))
        val prior = session(listOf(
            world("a", "root-a", 0.5, definition = definition(opponentMoves), rootFrame = rootA),
            world("b", "root-b", 0.5, definition = definition(opponentMoves), rootFrame = rootB),
        ))

        val result = NativeProductSessionReconciler { _, action -> action(worker) }
            .reconcile(prior, context(turn = 2), Long.MAX_VALUE)

        assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status, result.rootIssues.toString())
        val worlds = requireNotNull(result.sessionState).worlds
        assertEquals(1.0 / 3.0, worlds.filter { it.key.hypothesisId == "a" }.sumOf { it.probability }, 1e-12)
        assertEquals(2.0 / 3.0, worlds.filter { it.key.hypothesisId == "b" }.sumOf { it.probability }, 1e-12)
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
        var forcedDamageCalls = 0
        val choices = mutableListOf<Pair<String, String>>()

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame = error("must reuse root")

        override fun rebindMoves(
            snapshotJson: String,
            rebindings: List<jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding>,
        ): NativeBattleFrame = error("fixture does not expect move-set rebinding")

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
            branchCalls++
            choices += p1Choice to p2Choice
            return requireNotNull(results["$snapshotJson|$p2Choice"] ?: results[snapshotJson])
        }

        override fun branchWithForcedDamage(
            snapshotJson: String,
            p1Choice: String,
            p2Choice: String,
            forcedDamageRolls: List<NativeForcedDamageRoll>,
        ): NativeBattleFrame {
            forcedDamageCalls++
            val original = requireNotNull(results["$snapshotJson|$p2Choice"] ?: results[snapshotJson])
            val forcedByCall = forcedDamageRolls.associateBy(NativeForcedDamageRoll::damageCallIndex)
            val conditionedRolls = original.executedDamageRolls.map { roll ->
                val forced = forcedByCall[roll.damageCallIndex] ?: return@map roll
                val hpLoss = roll.possibleHpLosses[forced.percent - 85]
                roll.copy(actualHpLoss = hpLoss)
            }
            val hpById = conditionedRolls.associate { roll ->
                roll.targetPokemonUuid to (roll.hpBefore - roll.actualHpLoss)
            }
            fun patched(pokemon: NativePokemonFrame): NativePokemonFrame =
                hpById[pokemon.uuid]?.let { pokemon.copy(hp = it) } ?: pokemon
            return original.copy(
                snapshotJson = "$snapshotJson|forced-damage",
                p1Active = original.p1Active.map(::patched),
                p2Active = original.p2Active.map(::patched),
                p1Team = original.p1Team.map(::patched),
                p2Team = original.p2Team.map(::patched),
                executedDamageRolls = conditionedRolls,
            )
        }

        override fun close() = Unit
    }

    private fun session(
        worlds: List<NativeProductSessionWorld>,
        lastSequence: Long? = null,
        tier: BattleTrainerTier = BattleTrainerTier.BOSS,
    ) = NativeProductSessionState(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        rulesFingerprint = RULES,
        worlds = worlds,
        publicTurn = 1,
        lastObservedEventSequence = lastSequence,
        pendingOwnAction = PRODUCT_TACKLE,
        trainerTier = tier,
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
        opponentHpFraction: Double = 1.0,
    ) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("request-$turn".toByteArray()),
        state = state(turn, events, opponentHpFraction),
        candidates = listOf(PRODUCT_TACKLE),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun state(
        turn: Int,
        events: List<BattleObservedEventView>,
        opponentHpFraction: Double,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(
            publicPokemon(ALLY, BattleSide.ALLY),
            publicPokemon(OPPONENT, BattleSide.OPPONENT, opponentHpFraction),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun publicPokemon(id: UUID, side: BattleSide, hpFraction: Double = 1.0) = BattlePokemonStateView(
        id, side, 0, "cobblemon:mew", null, 50, hpFraction, null,
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

    private fun actionOrderTurnEvents() = listOf(
        BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.ACTION_ORDER,
            actorPokemonId = OPPONENT,
            publicValueId = "growl",
            baseMovePriority = 0,
        ),
        moveEvent(2),
        BattleObservedEventView(
            sequence = 3,
            turn = 1,
            kind = BattleObservedEventKind.ACTION_ORDER,
            actorPokemonId = ALLY,
            publicValueId = "tackle",
            baseMovePriority = 0,
        ),
        BattleObservedEventView(
            sequence = 4,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = ALLY,
            publicValueId = "tackle",
            actorSlot = 0,
        ),
    )

    private fun directDamageTurnEvents() = listOf(
        BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = ALLY,
            publicValueId = "tackle",
            actorSlot = 0,
        ),
        BattleObservedEventView(
            sequence = 2,
            turn = 1,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = OPPONENT,
            hpFractionDelta = -0.25,
            precedingActionSequence = 1,
            precedingActionActorPokemonId = ALLY,
            precedingActionMoveId = "tackle",
        ),
        moveEvent(3),
    )

    private fun damageRoll(actual: Int, possible: List<Int>) = NativeDamageRollFrame(
        turn = 1,
        attackerPokemonUuid = ALLY.toString(),
        targetPokemonUuid = OPPONENT.toString(),
        moveId = "tackle",
        hpBefore = 100,
        maxHp = 100,
        actualHpLoss = actual,
        possibleHpLosses = possible,
    )

    private fun moveOrder(first: UUID, second: UUID) = listOf(
        NativeExecutedMoveFrame(1, first.toString(), if (first == ALLY) "tackle" else "growl"),
        NativeExecutedMoveFrame(1, second.toString(), if (second == ALLY) "tackle" else "growl"),
    )

    private fun frame(
        snapshot: String,
        turn: Int,
        opponentHp: Int = 100,
        opponentMoves: List<String> = listOf("growl"),
        p1RequestJson: String = moveRequest(listOf("tackle")),
        p2RequestJson: String = moveRequest(opponentMoves),
        executedMoveOrder: List<NativeExecutedMoveFrame> = emptyList(),
        executedDamageRolls: List<NativeDamageRollFrame> = emptyList(),
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
            executedMoveOrder = executedMoveOrder,
            executedDamageRolls = executedDamageRolls,
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
