package jbro.cobblemon.morebattlecontent.api.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BattleDecisionContractTest {
    @Test
    fun `decision deadline allows a fifteen second router budget`() {
        assertEquals(20_000L, BattleBrainDefaults.DECISION_TIMEOUT_MILLIS)
    }

    @Test
    fun `decision must match the current request candidate and deadline`() {
        val requestId = UUID.randomUUID()
        val now = 1_000L
        val context = context(requestId, now + BattleBrainDefaults.DECISION_TIMEOUT_MILLIS)

        assertEquals(BattleDecisionValidationStatus.VALID, validate(context, decision(requestId), now))
        assertEquals(
            BattleDecisionValidationStatus.STALE_REQUEST,
            validate(context, decision(UUID.randomUUID()), now),
        )
        assertEquals(
            BattleDecisionValidationStatus.UNKNOWN_ACTION,
            validate(context, BattleDecision(requestId, "invented"), now),
        )
        assertEquals(
            BattleDecisionValidationStatus.DEADLINE_EXPIRED,
            validate(context, decision(requestId), context.deadlineEpochMillis + 1),
        )
    }

    @Test
    fun `decision contexts reject duplicate server action ids`() {
        val candidate = candidate()
        assertThrows(IllegalArgumentException::class.java) {
            context(UUID.randomUUID(), 10_000L, listOf(candidate, candidate))
        }
    }

    @Test
    fun `action candidates reject fields that contradict their kind`() {
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:move-switch",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                switchPokemonId = UUID.randomUUID(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:duplicate-composite",
                kind = BattleActionKind.COMPOSITE,
                componentActionIds = listOf("move:0", "move:0"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleActionCandidate(
                actionId = "invalid:wait-target",
                kind = BattleActionKind.WAIT,
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            )
        }
    }

    @Test
    fun `public AI contract does not expose Cobblemon implementation classes`() {
        val publicTypes = listOf(
            BattleBrainOpenContext::class.java,
            BattleDecisionContext::class.java,
            BattleStateView::class.java,
            BattleActionCandidate::class.java,
        )

        val exposedTypes = publicTypes.flatMap { type ->
            type.declaredFields.map { it.genericType.typeName }
        }
        assertFalse(exposedTypes.any { it.startsWith("com.cobblemon.") })
    }

    private fun context(
        requestId: UUID,
        deadline: Long,
        candidates: List<BattleActionCandidate> = listOf(candidate()),
    ) = BattleDecisionContext(
        requestId = requestId,
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
        candidates = candidates,
        deadlineEpochMillis = deadline,
    )

    private fun candidate() = BattleActionCandidate(
        actionId = "move:0",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
    )

    private fun decision(requestId: UUID) = BattleDecision(requestId, "move:0")

    private fun validate(
        context: BattleDecisionContext,
        decision: BattleDecision,
        now: Long,
    ) = BattleDecisionValidator.validate(context, decision, now)
}
