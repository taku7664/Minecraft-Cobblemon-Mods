package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

/** Engine-issued ordinary actions in a controlled level-mismatched ending, not a strength benchmark. */
@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedTeamRoleDecisionTest {
    @Test
    fun `team role evaluation does not abandon a native winning attack for reserves`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject.deepCopy()
        fun team(vararg members: JsonObject) = JsonObject().apply {
            add("sets", JsonArray().apply { members.forEach(::add) })
        }
        pair.add("p1", team(
            member("Alakazam", "Synchronize", 50, "psychic", "calmmind", "recover", "protect"),
            member("Umbreon", "Synchronize", 50, "bite", "confuseray", "moonlight", "protect"),
            member("Snorlax", "Immunity", 50, "bodyslam", "rest", "curse", "protect")))
        pair.add("p2", team(member("Machop", "Guts", 5, "leer", "focusenergy", "lowkick", "protect")))
        val battleId = UUID.nameUUIDFromBytes(pair["battleSeed"].toString().toByteArray())
        val boss = EmbeddedPolicyComparison.profileForSkill(5).let {
            it.copy(difficulty = it.difficulty.copy(lookaheadPlies = 2))
        }
        for (arm in listOf("CURRENT", "CURRENT_TEAM_COVERAGE")) {
            val observer = LocalDecisionTraceSelector(choiceSeedOverride = 0)
            val brain = LocalTacticalBrain(actionSelector = observer, tuning = EmbeddedPolicyComparison.tuning(arm))
            val session = brain.openSession(BattleBrainOpenContext(battleId, BattleFormat.SINGLE, trainerProfile = boss))
            try {
                EmbeddedTeamBattle.NativeSession(directory.resolve("audit/engine"), pair,
                    directory.resolve(arm), 5).use { native ->
                    val initial = native.read()
                    assertEquals("WAITING", initial["status"].asString)
                    val input = initial.getAsJsonArray("requests").map { it.asJsonObject }
                        .single { it["side"].asString == "p1" }
                    val context = EmbeddedTeamInput.context(input, battleId, initial["turn"].asInt, 0)
                    val nativeIds = input.getAsJsonArray("actions").map { it.asJsonObject["id"].asString }.toSet()
                    assertEquals(setOf("move 1", "move 2", "move 3", "move 4", "switch 2", "switch 3"), nativeIds)
                    assertEquals(nativeIds, context.candidates.map { it.actionId }.toSet(), "Do not narrow the engine request")
                    assertEquals(3, context.state.remainingPokemonBySide.getValue(BattleSide.ALLY))
                    assertEquals(1, context.state.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
                    val decision = brain.decide(session, context).toCompletableFuture().get()
                    assertEquals("move 1", decision.actionId, arm)
                    assertTrue("lookahead_turns_2" in decision.tags, arm)
                    assertFalse("lookahead_truncated" in decision.tags, arm)
                    val trace = requireNotNull(observer.latest)
                    assertEquals("move 1", trace.ranked.first().outcome.candidate.actionId, arm)
                    native.send(JsonObject().apply { add("choices", JsonObject().apply {
                        addProperty("p1", decision.actionId)
                        addProperty("p2", "move 1") // Controlled non-protect response, not an adversarial proof.
                    }) })
                    val result = native.read()
                    assertEquals("COMPLETE", result["status"].asString, arm)
                    assertEquals("p1", result["winner"].asString, arm)
                    assertTrue(result.getAsJsonArray("publicLog").any {
                        it.asString.startsWith("|move|p1a:") && "|Psychic|" in it.asString
                    }, arm)
                    native.awaitExit()
                    println("TEAM_ROLE_NATIVE_FINISH arm=$arm candidates=${nativeIds.size} action=${decision.actionId} winner=p1")
                }
            } finally {
                brain.closeSession(session, BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, 1))
            }
        }
    }

    private fun member(species: String, ability: String, level: Int, vararg moves: String) = JsonObject().apply {
        addProperty("species", species); addProperty("ability", ability); addProperty("level", level)
        addProperty("nature", "Timid"); addProperty("gender", "M")
        add("moves", JsonArray().apply { moves.forEach(::add) })
        add("evs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 0) } })
        add("ivs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 31) } })
    }
}
