package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalIdleUtilityMoveRules
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A singles Protect earns its turn only when waiting pays: residual damage on the foe, own recovery, or a Wish. */
class LocalPurposelessProtectTest {
    @Test
    fun `protect is purposeless unless waiting a turn pays`() {
        assertEquals(true, purposeless())
        assertEquals(false, purposeless(foeStatus = "tox"))
        assertEquals(false, purposeless(foeStatus = "brn"))
        assertEquals(false, purposeless(foeVolatiles = setOf("leechseed")))
        assertEquals(false, purposeless(ownItem = "leftovers"))
        assertEquals(false, purposeless(ownAbility = "speedboost"))
        assertEquals(false, purposeless(ownWishLastTurn = true))
        assertEquals(true, purposeless(foeStatus = "par"), "Paralysis deals no damage while the user waits")
        assertEquals(false, purposeless(moveId = "spikyshield"), "A shield with a contact effect keeps its own gain")
        assertEquals(false, purposeless(format = BattleFormat.DOUBLE), "Doubles keep Protect for the partner")
    }

    private fun purposeless(
        moveId: String = "protect",
        foeStatus: String? = null,
        foeVolatiles: Set<String> = emptySet(),
        ownItem: String? = null,
        ownAbility: String? = null,
        ownWishLastTurn: Boolean = false,
        format: BattleFormat = BattleFormat.SINGLE,
    ): Boolean {
        val own = mon(BattleSide.ALLY, null, ownItem, ownAbility, emptySet())
        val foe = mon(BattleSide.OPPONENT, foeStatus, null, null, foeVolatiles)
        val candidate = BattleActionCandidate(actionId = moveId, kind = BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 0, moveId = moveId, targets = emptyList())
        val events = if (ownWishLastTurn) listOf(BattleObservedEventView(1, 4, BattleObservedEventKind.MOVE_USED,
            actorPokemonId = own.battlePokemonId, publicValueId = "wish")) else emptyList()
        val state = BattleStateView(UUID.randomUUID(), format, 5, listOf(own, foe), BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, events, emptyList())
        return LocalIdleUtilityMoveRules.purposelessProtect(candidate,
            BattleDecisionContext(UUID.randomUUID(), state, listOf(candidate), Long.MAX_VALUE))
    }

    private fun mon(side: BattleSide, status: String?, item: String?, ability: String?, volatiles: Set<String>) =
        BattlePokemonStateView(
            UUID.randomUUID(), side, 0, "probe", null, 50, 0.6, status, emptyMap(), emptySet(), ability, item,
            false, setOf("water"), knownVolatileEffectIds = volatiles,
        )
}
