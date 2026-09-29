package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BrainCapability
import jbro.cobblemon.mcc.internal.ai.BrainId
import jbro.cobblemon.mcc.betterai.router.BetterAiConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetterAiModuleContractTest {
    @Test
    fun `addon registers one local tactical provider for single and double`() {
        val registry = BattleBrainRegistry.create()
        MoreCobblemonContentsBetterAi.register(registry, BetterAiConfig())

        val provider = registry.find(
            BrainId("more_cobblemon_contents:local_tactical"),
            BrainCapability.DOUBLE,
        )

        assertNotNull(provider)
        assertEquals(BattleBrainProviderRole.LOCAL, provider?.role)
        assertEquals(setOf(BrainCapability.SINGLE, BrainCapability.DOUBLE), provider?.capabilities)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `enabled complete config registers Router primary beside local fallback`() {
        val registry = BattleBrainRegistry.create()
        MoreCobblemonContentsBetterAi.register(
            registry,
            BetterAiConfig(enabled = true, apiKey = "test-key", model = "test/model"),
        )

        assertEquals(2, registry.all().size)
        assertEquals(
            BattleBrainProviderRole.PRIMARY,
            registry.find(
                BrainId("more_cobblemon_contents:openrouter_humanlike"),
                BrainCapability.SINGLE,
            )?.role,
        )

        val router = registry.find(
            BrainId("more_cobblemon_contents:openrouter_humanlike"),
            BrainCapability.SINGLE,
        )!!
        assertTrue(router.isEligible(selection(BattleBrainContentIds.BATTLE_TOWER, BattleEncounterRole.BOSS)))
        org.junit.jupiter.api.Assertions.assertFalse(
            router.isEligible(selection(BattleBrainContentIds.BATTLE_TOWER, BattleEncounterRole.REGULAR)),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            router.isEligible(
                selection(
                    BattleBrainContentIds.BATTLE_FACTORY,
                    BattleEncounterRole.REGULAR,
                    BattleTrainerTier.BOSS,
                ),
            ),
        )
        org.junit.jupiter.api.Assertions.assertFalse(router.isEligible(selection("example:unknown", BattleEncounterRole.BOSS)))
    }

    private fun selection(
        contentId: String,
        encounterRole: BattleEncounterRole,
        tier: BattleTrainerTier = BattleTrainerTier.BOSS,
    ) = BattleBrainSelectionContext(contentId, encounterRole, tier)
}
