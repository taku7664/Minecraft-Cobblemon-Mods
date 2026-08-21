package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.ai.BrainId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetterAiModuleContractTest {
    @Test
    fun `addon metadata requires a compatible core and stays server only`() {
        val stream = javaClass.classLoader.getResourceAsStream("fabric.mod.json")
        assertNotNull(stream, "fabric.mod.json must be packaged")

        val root = stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("cobblemon_more_battle_content_better_ai", root["id"].asString)
        assertEquals("Cobblemon: More Battle Content - Better AI", root["name"].asString)
        assertEquals("server", root["environment"].asString)

        val main = root.getAsJsonObject("entrypoints").getAsJsonArray("main")[0].asJsonObject
        assertEquals("kotlin", main["adapter"].asString)
        assertEquals("jbro.cobblemon.morebattlecontent.betterai.MoreBattleContentBetterAi", main["value"].asString)

        val depends = root.getAsJsonObject("depends")
        assertEquals(">=1.2.1 <2.0.0", depends["cobblemon_more_battle_content"].asString)
        assertTrue(depends.has("fabric-language-kotlin"))

        assertDoesNotThrow { Class.forName(main["value"].asString) }
    }

    @Test
    fun `addon registers one local tactical provider for single and double`() {
        val registry = BattleBrainRegistry.create()
        MoreBattleContentBetterAi.register(registry, BetterAiConfig())

        val provider = registry.find(
            BrainId("cobblemon_more_battle_content_better_ai:local_tactical"),
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
        MoreBattleContentBetterAi.register(
            registry,
            BetterAiConfig(enabled = true, apiKey = "test-key", model = "test/model"),
        )

        assertEquals(2, registry.all().size)
        assertEquals(
            BattleBrainProviderRole.PRIMARY,
            registry.find(
                BrainId("cobblemon_more_battle_content_better_ai:openrouter_humanlike"),
                BrainCapability.SINGLE,
            )?.role,
        )

        val router = registry.find(
            BrainId("cobblemon_more_battle_content_better_ai:openrouter_humanlike"),
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
