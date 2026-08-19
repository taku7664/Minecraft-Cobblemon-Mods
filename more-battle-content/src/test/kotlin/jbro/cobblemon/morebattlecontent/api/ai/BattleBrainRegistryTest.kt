package jbro.cobblemon.morebattlecontent.api.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BattleBrainRegistryTest {
    @Test
    fun `global registry is shared while create returns an isolated registry`() {
        assertSame(BattleBrainRegistry.global(), BattleBrainRegistry.global())
        org.junit.jupiter.api.Assertions.assertNotSame(BattleBrainRegistry.global(), BattleBrainRegistry.create())
    }

    @Test
    fun `registry rejects duplicate namespaced ids without replacing the first provider`() {
        val registry = BattleBrainRegistry.create()
        val original = provider("example:local", setOf(BrainCapability.SINGLE, BrainCapability.DOUBLE))
        val duplicate = provider("example:local", setOf(BrainCapability.SINGLE))

        assertEquals(BattleBrainRegistrationStatus.REGISTERED, registry.register(original))
        assertEquals(BattleBrainRegistrationStatus.DUPLICATE_ID, registry.register(duplicate))
        assertSame(original, registry.find(BrainId("example:local"), BrainCapability.DOUBLE))
        assertNull(registry.find(BrainId("example:missing"), BrainCapability.SINGLE))
        assertEquals(listOf(original), registry.all())
    }

    @Test
    fun `brain ids and provider capabilities reject malformed public registrations`() {
        assertThrows(IllegalArgumentException::class.java) { BrainId("local") }
        assertThrows(IllegalArgumentException::class.java) { BrainId("Example:local") }
        assertThrows(IllegalArgumentException::class.java) {
            BattleBrainProvider(BrainId("example:empty"), emptySet(), BattleBrainFactory(::StubBrain))
        }
    }

    @Test
    fun `provider selection policy receives content encounter role and difficulty without changing legacy providers`() {
        val regularBossDifficulty = BattleBrainSelectionContext(
            contentId = BattleBrainContentIds.BATTLE_FACTORY,
            encounterRole = BattleEncounterRole.REGULAR,
            difficultyTier = BattleTrainerTier.BOSS,
        )
        val actualBoss = BattleBrainSelectionContext(
            contentId = BattleBrainContentIds.BATTLE_FACTORY,
            encounterRole = BattleEncounterRole.BOSS,
            difficultyTier = BattleTrainerTier.BOSS,
        )
        val bossOnly = BattleBrainProvider(
            id = BrainId("example:boss_only"),
            capabilities = setOf(BrainCapability.SINGLE),
            factory = BattleBrainFactory(::StubBrain),
            role = BattleBrainProviderRole.PRIMARY,
            selectionPolicy = BattleBrainSelectionPolicy { context ->
                context.encounterRole == BattleEncounterRole.BOSS
            },
        )

        assertFalse(bossOnly.isEligible(regularBossDifficulty))
        assertTrue(bossOnly.isEligible(actualBoss))
        assertTrue(provider("example:legacy", setOf(BrainCapability.SINGLE)).isEligible(regularBossDifficulty))
        assertThrows(IllegalArgumentException::class.java) {
            BattleBrainSelectionContext("factory", BattleEncounterRole.REGULAR, BattleTrainerTier.STANDARD)
        }
    }

    private fun provider(id: String, capabilities: Set<BrainCapability>) = BattleBrainProvider(
        id = BrainId(id),
        capabilities = capabilities,
        factory = BattleBrainFactory(::StubBrain),
    )

    private class StubSession(override val sessionId: UUID = UUID.randomUUID()) : BattleBrainSession

    private class StubBrain : BattleBrain {
        override fun openSession(context: BattleBrainOpenContext): BattleBrainSession = StubSession()

        override fun decide(
            session: BattleBrainSession,
            context: BattleDecisionContext,
        ) = CompletableFuture.completedFuture(BattleDecision(context.requestId, context.candidates.first().actionId))

        override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) = Unit
    }
}
