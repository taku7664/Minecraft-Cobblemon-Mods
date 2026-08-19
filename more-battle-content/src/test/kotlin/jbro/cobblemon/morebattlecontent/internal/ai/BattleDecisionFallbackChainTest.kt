package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecision
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BattleDecisionFallbackChainTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val brainExecutor = Executors.newCachedThreadPool()

    @AfterEach
    fun closeExecutors() {
        scheduler.shutdownNow()
        brainExecutor.shutdownNow()
    }

    @Test
    fun `valid primary decision wins without waiting for local brain`() {
        val now = 1_000L
        val context = context(now + 5_000L)
        val pendingLocal = CompletableFuture<BattleDecision>()
        val chain = chain(now)

        val result = chain.decide(
            primary = endpoint { CompletableFuture.completedFuture(decision(context, "move:1")) },
            local = endpoint { pendingLocal },
            context = context,
        ).toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.PRIMARY_BRAIN, result.source)
        assertEquals("move:1", result.decision?.actionId)
        assertTrue(result.failures.isEmpty())
        assertFalse(pendingLocal.isDone)
    }

    @Test
    fun `invalid primary uses concurrently prepared local decision`() {
        val now = 1_000L
        val context = context(now + 5_000L)
        val chain = chain(now)

        val result = chain.decide(
            primary = endpoint { CompletableFuture.completedFuture(decision(context, "invented")) },
            local = endpoint { CompletableFuture.completedFuture(decision(context, "move:1")) },
            context = context,
        ).toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.LOCAL_BRAIN, result.source)
        assertEquals("move:1", result.decision?.actionId)
        assertEquals(
            listOf(BattleDecisionFailure(BattleDecisionStage.PRIMARY, BattleDecisionFailureReason.UNKNOWN_ACTION)),
            result.failures,
        )
    }

    @Test
    fun `both brain failures request baseline instead of choosing emergency action`() {
        val now = 1_000L
        val context = context(now + 5_000L)
        val chain = chain(now)

        val result = chain.decide(
            primary = endpoint { CompletableFuture.failedFuture(IllegalStateException("remote unavailable")) },
            local = endpoint { CompletableFuture.completedFuture(decision(context, "invented")) },
            context = context,
        ).toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, result.source)
        assertNull(result.decision)
        assertEquals(
            listOf(
                BattleDecisionFailure(BattleDecisionStage.PRIMARY, BattleDecisionFailureReason.BRAIN_FAILURE),
                BattleDecisionFailure(BattleDecisionStage.LOCAL, BattleDecisionFailureReason.UNKNOWN_ACTION),
            ),
            result.failures,
        )
    }

    @Test
    fun `missing optional brains requests baseline immediately`() {
        val context = context(6_000L)
        val result = chain(1_000L).decide(null, null, context)
            .toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, result.source)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `local brain works when primary is not configured`() {
        val context = context(6_000L)
        val result = chain(1_000L).decide(
            primary = null,
            local = endpoint { CompletableFuture.completedFuture(decision(context, "move:0")) },
            context = context,
        ).toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.LOCAL_BRAIN, result.source)
        assertEquals("move:0", result.decision?.actionId)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `expired context never invokes a brain`() {
        var called = false
        val context = context(999L)

        val result = chain(1_000L).decide(
            primary = endpoint {
                called = true
                CompletableFuture.completedFuture(decision(context, "move:0"))
            },
            local = null,
            context = context,
        ).toCompletableFuture().get(1, TimeUnit.SECONDS)

        assertFalse(called)
        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, result.source)
        assertEquals(BattleDecisionFailureReason.DEADLINE_EXPIRED, result.failures.single().reason)
    }

    @Test
    fun `blocking brain invocation cannot block caller or deadline`() {
        val release = CountDownLatch(1)
        val context = context(System.currentTimeMillis() + 5_000L)
        val coordinator = BattleBrainDecisionCoordinator(
            scheduler = scheduler,
            brainExecutor = brainExecutor,
            maximumDecisionMillis = 30L,
        )
        val chain = BattleDecisionFallbackChain(coordinator)
        val startedAt = System.nanoTime()

        val future = chain.decide(
            primary = endpoint {
                release.await()
                CompletableFuture.completedFuture(decision(context, "move:1"))
            },
            local = null,
            context = context,
        ).toCompletableFuture()
        val callMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val result = future.get(1, TimeUnit.SECONDS)
        release.countDown()

        assertTrue(callMillis < 100L)
        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, result.source)
        assertEquals(BattleDecisionFailureReason.TIMEOUT, result.failures.single().reason)
    }

    @Test
    fun `blocking session creation is also isolated behind the deadline`() {
        val release = CountDownLatch(1)
        val context = context(System.currentTimeMillis() + 5_000L)
        val coordinator = BattleBrainDecisionCoordinator(
            scheduler = scheduler,
            brainExecutor = brainExecutor,
            maximumDecisionMillis = 30L,
        )
        val brain = brain { CompletableFuture.completedFuture(decision(context, "move:0")) }
        val endpoint = BattleBrainEndpoint(brain) {
            release.await()
            Session
        }
        val startedAt = System.nanoTime()

        val future = BattleDecisionFallbackChain(coordinator).decide(endpoint, null, context).toCompletableFuture()
        val callMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val result = future.get(1, TimeUnit.SECONDS)
        release.countDown()

        assertTrue(callMillis < 100L)
        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, result.source)
        assertEquals(BattleDecisionFailureReason.TIMEOUT, result.failures.single().reason)
    }

    @Test
    fun `late primary completion cannot replace baseline result`() {
        val context = context(System.currentTimeMillis() + 5_000L)
        val pending = CompletableFuture<BattleDecision>()
        val coordinator = BattleBrainDecisionCoordinator(
            scheduler = scheduler,
            brainExecutor = brainExecutor,
            maximumDecisionMillis = 30L,
        )
        val future = BattleDecisionFallbackChain(coordinator)
            .decide(endpoint { pending }, null, context)
            .toCompletableFuture()

        val first = future.get(1, TimeUnit.SECONDS)

        assertEquals(BattleDecisionSource.BASELINE_REQUIRED, first.source)
        assertEquals(first, future.get())
        assertThrows(CancellationException::class.java) {
            pending.get(1, TimeUnit.SECONDS)
        }
        assertTrue(pending.isCancelled)
    }

    @Test
    fun `emergency decision is explicit and uses only a server candidate`() {
        val context = context(6_000L)
        val failures = listOf(
            BattleDecisionFailure(BattleDecisionStage.PRIMARY, BattleDecisionFailureReason.TIMEOUT),
        )

        val emergency = BattleDecisionResolution.emergency(context, failures)

        assertEquals(BattleDecisionSource.EMERGENCY_ACTION, emergency.source)
        assertEquals(context.requestId, emergency.decision?.requestId)
        assertEquals("move:0", emergency.decision?.actionId)
        assertEquals(setOf("cobblemon_more_battle_content:emergency_fallback"), emergency.decision?.tags)
        assertEquals(failures, emergency.failures)
    }

    private fun chain(now: Long) = BattleDecisionFallbackChain(
        BattleBrainDecisionCoordinator(scheduler, brainExecutor, nowEpochMillis = { now }),
    )

    private fun context(deadline: Long) = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = emptyList(),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        ),
        candidates = listOf(candidate("move:0"), candidate("move:1")),
        deadlineEpochMillis = deadline,
    )

    private fun candidate(id: String) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "tackle",
    )

    private fun decision(context: BattleDecisionContext, actionId: String) =
        BattleDecision(context.requestId, actionId, 1.0)

    private fun endpoint(decide: () -> CompletionStage<BattleDecision>) = BattleBrainEndpoint(
        brain = brain(decide),
        session = Session,
    )

    private fun brain(decide: () -> CompletionStage<BattleDecision>) = object : BattleBrain {
            override fun openSession(context: BattleBrainOpenContext): BattleBrainSession = Session
            override fun decide(
                session: BattleBrainSession,
                context: BattleDecisionContext,
            ): CompletionStage<BattleDecision> = decide()

            override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) = Unit
        }

    private object Session : BattleBrainSession {
        override val sessionId: UUID = UUID.randomUUID()
    }
}
