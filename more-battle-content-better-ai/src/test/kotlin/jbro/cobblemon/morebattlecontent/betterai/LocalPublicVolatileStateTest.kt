package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalBattleStateFingerprint
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfig
import jbro.cobblemon.morebattlecontent.betterai.router.HumanlikePromptCodec
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalPublicVolatileStateTest {
    private val actor = "p2a: 00000000-0000-0000-0000-000000000203"
    private fun context(vararg lines: String): BattleDecisionContext {
        val input = javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        lines.forEach(input.getAsJsonArray("publicLog")::add)
        return EmbeddedTeamInput.context(input, UUID(0, 43), 5, 0)
    }
    private fun active(context: BattleDecisionContext) = context.state.pokemon.single {
        it.side == BattleSide.OPPONENT && it.activeSlot == 0
    }

    @Test
    fun `native input preserves starts and ends and distinguishes transfer from ordinary switch`() {
        assertEquals(setOf("substitute"), active(context("|-start|$actor|Substitute")).knownVolatileEffectIds)
        assertTrue(active(context("|-start|$actor|Substitute", "|-end|$actor|Substitute")).knownVolatileEffectIds.isEmpty())
        for ((source, inherited) in listOf("Baton Pass" to true, "Shed Tail" to true, "U-turn" to false)) {
            val context = context("|-start|$actor|Substitute",
                "|switch|p2a: 00000000-0000-0000-0000-000000000202|Leavanny, L50, M|100/100|[from] $source")
            assertEquals(inherited, "substitute" in active(context).knownVolatileEffectIds)
            assertTrue(context.state.pokemon.filter { it.activeSlot == null }.all { it.knownVolatileEffectIds.isEmpty() })
        }
        assertTrue(active(context("|-start|$actor|Substitute", "|faint|$actor")).knownVolatileEffectIds.isEmpty())
    }

    @Test
    fun `state copies and cache key retain observed effect while ordinary switch clears it`() {
        val context = context("|-start|$actor|Substitute")
        val original = active(context)
        assertEquals(original.knownVolatileEffectIds, original.copyState(hpFraction = 0.1).knownVolatileEffectIds)
        assertTrue(original.copyState(fainted = true, hpFraction = 0.0).knownVolatileEffectIds.isEmpty())
        val cleared = context.state.copyState(pokemon = context.state.pokemon.map {
            it.copyState(knownVolatileEffectIds = emptySet())
        })
        val fingerprint = LocalBattleStateFingerprint()
        assertNotEquals(fingerprint.of(context.state), fingerprint.of(cleared))
        val incoming = context.state.pokemon.first { it.side == BattleSide.OPPONENT && it.activeSlot == null && !it.fainted }
        val switched = LocalSwitchStateProjector.project(context.state, BattleSide.OPPONENT,
            BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = incoming.battlePokemonId))
        assertTrue(switched.pokemon.filter { it.side == BattleSide.OPPONENT }.all { it.knownVolatileEffectIds.isEmpty() })
    }

    @Test
    fun `cache key distinguishes defensive base stab and Tera type channels`() {
        val context = context()
        val target = active(context)
        val fingerprint = LocalBattleStateFingerprint()
        fun withTarget(replacement: BattlePokemonStateView) = context.state.copyState(
            pokemon = context.state.pokemon.map {
                if (it.battlePokemonId == target.battlePokemonId) replacement else it
            },
        )
        val teraFire = withTarget(target.copyState(
            knownTypeIds = setOf("fire"),
            knownBaseStabTypeIds = setOf("electric"),
            knownTeraTypeId = "fire",
        ))
        val differentBaseStab = withTarget(target.copyState(
            knownTypeIds = setOf("fire"),
            knownBaseStabTypeIds = setOf("ghost"),
            knownTeraTypeId = "fire",
        ))
        val differentTera = withTarget(target.copyState(
            knownTypeIds = setOf("fire"),
            knownBaseStabTypeIds = setOf("electric"),
            knownTeraTypeId = "water",
        ))

        assertNotEquals(fingerprint.of(teraFire), fingerprint.of(differentBaseStab))
        assertNotEquals(fingerprint.of(teraFire), fingerprint.of(differentTera))
    }

    @Test
    fun `router receives public effect facts without a failure or action recommendation`() {
        val context = context("|-start|$actor|Substitute")
        val request = JsonParser.parseString(HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            BattleBrainOpenContext(context.state.battleId, BattleFormat.SINGLE), context)).asJsonObject
        val messages = request.getAsJsonArray("messages")
        val digest = JsonParser.parseString(messages[1].asJsonObject["content"].asString).asJsonObject
        val target = digest.getAsJsonArray("board").map { it.asJsonObject }.single {
            it["side"].asString == "OPPONENT" && it["activeSlot"]?.isJsonNull == false
        }
        assertEquals(listOf("substitute"), target.getAsJsonArray("knownVolatileEffectIds").map { it.asString })
        assertFalse(target.has("recommendedAction"))
        assertFalse(target.has("publiclyInert"))
        assertTrue(messages[0].asJsonObject["content"].asString.contains("allow same-turn recreation"))
    }
}
