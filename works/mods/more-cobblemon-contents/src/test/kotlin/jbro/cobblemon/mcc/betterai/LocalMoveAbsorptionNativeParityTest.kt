package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.betterai.engine.EngineReferee
import jbro.cobblemon.mcc.betterai.mechanics.LocalMoveAbsorption
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalMoveAbsorptionNativeParityTest {
    @Test
    fun `TryHit healing boosts and suppression agree with native Showdown for every absorption family`() {
        EngineReferee.assumeAvailable()
        val tool = Path.of(System.getProperty("aiengine.tools")).resolve("absorption-audit.cjs")
        val process = ProcessBuilder("node", tool.toString(), System.getProperty("aiengine.showdown"))
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native absorption audit timed out")
        assertEquals(0, process.exitValue(), text)
        val cases = JsonParser.parseString(text).asJsonObject.getAsJsonArray("cases")
        assertEquals(88, cases.size())
        for (raw in cases) {
            val row = raw.asJsonObject
            val ability = row["ability"].asString
            val mode = row["mode"].asString
            val label = "$ability/$mode"
            val before = row.getAsJsonObject("before")
            fun mon(side: BattleSide, trait: String?, hp: Double, item: String?, volatile: Set<String>) =
                BattlePokemonStateView(UUID(0, if (side == BattleSide.ALLY) 1 else 2), side, 0, "mew", null, 50,
                    hp, null, emptyMap(), emptySet(), trait, item, false, knownTypeIds = setOf("psychic"),
                    knownVolatileEffectIds = volatile, combatStats = BattleCombatStatRangesView(
                        BattleIntegerRange(146, 146), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                        BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                        if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
            val target = mon(BattleSide.ALLY, ability, before["hp"].asDouble / before["maxhp"].asDouble,
                if (mode == "shield") "abilityshield" else null,
                when (mode) { "healblock" -> setOf("healblock"); "gastroacid" -> setOf("gastroacid")
                    "dynamax" -> setOf("dynamax"); else -> emptySet() })
            val actor = mon(BattleSide.OPPONENT, when (mode) { "moldbreaker", "shield" -> "moldbreaker"
                "gas" -> "neutralizinggas"; else -> null }, 1.0, null, emptySet())
            val state = BattleStateView(UUID(0, 3), BattleFormat.SINGLE, 1, listOf(target, actor),
                BattleFieldStateView.empty(), BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
            val action = BattleActionCandidate("absorption", BattleActionKind.USE_MOVE, 0, 0, row["move"].asString,
                moveDetails = BattleMoveCandidateView(row["type"].asString, BattleMoveDamageCategory.SPECIAL,
                    80.0, 100.0, 0, 10, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        emptyList(), false, mechanicFlags = row.getAsJsonArray("flags").map { it.asString }.toSet())))
            val reaction = LocalMoveAbsorption.apply(state, action, actor, target)
            if (mode in setOf("moldbreaker", "gastroacid", "gas")) {
                assertNull(reaction, label)
                continue
            }
            assertNotNull(reaction, label)
            val actual = reaction!!.pokemon.first { it.side == BattleSide.ALLY }
            val native = row.getAsJsonObject("after")
            assertEquals(native["hp"].asDouble / native["maxhp"].asDouble, actual.hpFraction, 1e-12, label)
            val stats = mapOf("atk" to "attack", "def" to "defence", "spa" to "special_attack",
                "spd" to "special_defence", "spe" to "speed", "accuracy" to "accuracy", "evasion" to "evasion")
            val expected = native.getAsJsonObject("boosts").entrySet().filter { it.value.asInt != 0 }
                .associate { stats.getValue(it.key) to it.value.asInt }
            assertEquals(expected, actual.statStages, label)
            assertEquals(native["flashfire"].asBoolean, "flashfire" in actual.knownVolatileEffectIds, label)
        }
    }
}
