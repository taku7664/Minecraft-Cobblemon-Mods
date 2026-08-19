package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Cobblemon173BrainProviderResolverTest {
    @Test
    fun `resolver does not instantiate a primary provider rejected by battle selection policy`() {
        val registry = BattleBrainRegistry.create()
        val creations = AtomicInteger()
        registry.register(
            BattleBrainProvider(
                id = BrainId("example:boss_router"),
                capabilities = setOf(BrainCapability.SINGLE),
                factory = BattleBrainFactory {
                    creations.incrementAndGet()
                    StubBrain()
                },
                role = BattleBrainProviderRole.PRIMARY,
                selectionPolicy = BattleBrainSelectionPolicy { it.encounterRole == BattleEncounterRole.BOSS },
            ),
        )

        val regular = Cobblemon173BrainProviderResolver.create(
            registry,
            BrainCapability.SINGLE,
            BattleBrainProviderRole.PRIMARY,
            selection(BattleEncounterRole.REGULAR),
        )
        val boss = Cobblemon173BrainProviderResolver.create(
            registry,
            BrainCapability.SINGLE,
            BattleBrainProviderRole.PRIMARY,
            selection(BattleEncounterRole.BOSS),
        )

        assertNull(regular)
        assertNotNull(boss)
        assertEquals(1, creations.get())
    }

    @Test
    fun `resolver fails closed when an external provider selection policy throws`() {
        val registry = BattleBrainRegistry.create()
        registry.register(
            BattleBrainProvider(
                id = BrainId("example:broken_policy"),
                capabilities = setOf(BrainCapability.SINGLE),
                factory = BattleBrainFactory(::StubBrain),
                role = BattleBrainProviderRole.PRIMARY,
                selectionPolicy = BattleBrainSelectionPolicy { error("broken") },
            ),
        )

        assertNull(
            Cobblemon173BrainProviderResolver.create(
                registry,
                BrainCapability.SINGLE,
                BattleBrainProviderRole.PRIMARY,
                selection(BattleEncounterRole.BOSS),
            ),
        )
    }

    private fun selection(role: BattleEncounterRole) = BattleBrainSelectionContext(
        BattleBrainContentIds.BATTLE_TOWER,
        role,
        BattleTrainerTier.BOSS,
    )

    private class StubSession(override val sessionId: UUID = UUID.randomUUID()) : BattleBrainSession

    private class StubBrain : BattleBrain {
        override fun openSession(context: BattleBrainOpenContext): BattleBrainSession = StubSession()

        override fun decide(session: BattleBrainSession, context: BattleDecisionContext) =
            CompletableFuture.completedFuture(BattleDecision(context.requestId, context.candidates.first().actionId))

        override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) = Unit
    }
}
