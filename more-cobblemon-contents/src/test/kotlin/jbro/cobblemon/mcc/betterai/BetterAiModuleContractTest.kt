package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BrainCapability
import jbro.cobblemon.mcc.internal.ai.BrainId
import jbro.cobblemon.mcc.betterai.router.BetterAiConfig
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetterAiModuleContractTest {
    @Test
    fun `addon metadata requires a compatible core and loads for integrated servers`() {
        val stream = javaClass.classLoader.getResourceAsStream("fabric.mod.json")
        assertNotNull(stream, "fabric.mod.json must be packaged")

        val root = stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("more_cobblemon_contents", root["id"].asString)
        assertEquals("More Cobblemon Contents Better AI", root["name"].asString)
        assertEquals("*", root["environment"].asString)

        val main = root.getAsJsonObject("entrypoints").getAsJsonArray("main")[0].asJsonObject
        assertEquals("kotlin", main["adapter"].asString)
        assertEquals("jbro.cobblemon.mcc.betterai.MoreCobblemonContentsBetterAi", main["value"].asString)

        val depends = root.getAsJsonObject("depends")
        assertEquals(">=0.19.5", depends["fabricloader"].asString)
        assertEquals(">=1.14.1+kotlin.2.4.20", depends["fabric-language-kotlin"].asString)
        assertEquals(">=1.6.21 <2.0.0", depends["more_cobblemon_contents"].asString)

        assertDoesNotThrow { Class.forName(main["value"].asString) }
    }

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
