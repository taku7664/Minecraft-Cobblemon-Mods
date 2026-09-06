package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedTeamBattleTest {
    @Test
    fun `native recharge encore and taunt announcements reach public constraints`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject.deepCopy()
        fun fixture(species: String, ability: String, moves: List<String>) = JsonObject().apply {
            addProperty("species", species); addProperty("ability", ability); addProperty("level", 50)
            addProperty("nature", "Timid"); addProperty("gender", if (species == "Blissey") "F" else "M"); addProperty("item", "Leftovers")
            add("moves", com.google.gson.JsonArray().apply { moves.forEach(::add) })
            add("evs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 0) } })
            add("ivs", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 31) } })
        }
        pair.add("p1", JsonObject().apply { add("sets", com.google.gson.JsonArray().apply {
            add(fixture("Smeargle", "Own Tempo", listOf("taunt", "encore", "hyperbeam", "bind")))
        }) })
        pair.add("p2", JsonObject().apply { add("sets", com.google.gson.JsonArray().apply {
            add(fixture("Blissey", "Natural Cure", listOf("softboiled", "seismictoss", "protect", "thunderwave")))
        }) })
        EmbeddedTeamBattle.NativeSession(directory.resolve("audit/engine"), pair, directory.resolve("protocol"), 20).use { native ->
            var frame = native.read()
            fun context(): BattleDecisionContext {
                val input = frame.getAsJsonArray("requests").map { it.asJsonObject }.single { it["side"].asString == "p1" }
                return EmbeddedTeamInput.context(input, UUID(0, 7), frame["turn"].asInt, 0)
            }
            fun step(action: String) {
                native.send(JsonObject().apply { add("choices", JsonObject().apply {
                    addProperty("p1", action); addProperty("p2", "move 1")
                }) })
                frame = native.read()
                assertEquals("WAITING", frame["status"].asString)
            }
            fun constraint(side: BattleSide) = context().state.pokemon.single { it.side == side }.actionConstraints
            var attempts = 0
            while (!constraint(BattleSide.ALLY).mustRecharge && attempts++ < 4) step("move 3")
            assertTrue(constraint(BattleSide.ALLY).mustRecharge)
            step("move 1") // Native forced recharge slot, not a scripted fallback.
            assertFalse(constraint(BattleSide.ALLY).mustRecharge)
            assertTrue(context().state.observedEvents.any {
                it.moveOutcome?.kind == BattleMoveOutcomeKind.CANNOT_ACT && it.moveOutcome?.publicEffectId == "recharge"
            })
            step("move 2")
            assertEquals("softboiled", constraint(BattleSide.OPPONENT).encoreMoveId)
            step("move 1")
            assertTrue(constraint(BattleSide.OPPONENT).taunted)
        }
    }

    @Test
    fun `sampled complete teams receive both local brains through native battle end`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject
        val result = EmbeddedTeamBattle.run(directory.resolve("audit/engine"), pair, directory.resolve("battle"))
        assertEquals("COMPLETE", result["status"].asString)
        assertTrue(result["decisions"].asInt > 2)
        assertTrue(result.getAsJsonObject("decisionsBySide")["p1"].asInt > 0)
        assertTrue(result.getAsJsonObject("decisionsBySide")["p2"].asInt > 0)
        assertTrue(result["forcedSwitchDecisions"].asInt > 0)
        assertEquals(0, result["illegalChoices"].asInt)
        assertTrue(result["effectAnnotatedCandidates"].asInt > 0)
        assertTrue(result.getAsJsonArray("publicLog").none { it.asString.contains("pp_update") })
    }

    @Test
    fun `unrevealed bench changes remain outside opponent input and illegal actions are never replaced`(@TempDir directory: Path) {
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 1)
        val pair = audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0].asJsonObject
        val changed = pair.deepCopy()
        changed.getAsJsonObject("p2").getAsJsonArray("sets")[2].asJsonObject.apply {
            getAsJsonArray("moves").set(0, JsonPrimitive("splash"))
            addProperty("item", "Heavy-Duty Boots")
            getAsJsonObject("ivs").addProperty("atk", 0)
        }
        fun first(value: JsonObject, name: String): JsonObject = EmbeddedTeamBattle.NativeSession(
            directory.resolve("audit/engine"), value, directory.resolve(name), 2).use { it.read() }
        val original = first(pair, "original")
        val variant = first(changed, "changed")
        fun request(frame: JsonObject, side: String) = frame.getAsJsonArray("requests").map { it.asJsonObject }.single { it["side"].asString == side }
        assertEquals(request(original, "p1"), request(variant, "p1"))
        assertNotEquals(request(original, "p2"), request(variant, "p2"))
        for (side in listOf("p1", "p2")) {
            val context = EmbeddedTeamInput.context(request(original, side), UUID(0, 1), original["turn"].asInt, 0)
            assertEquals(3, context.state.pokemon.count { it.side == BattleSide.ALLY })
            assertEquals(1, context.state.pokemon.count { it.side == BattleSide.OPPONENT })
            assertTrue(context.state.pokemon.filter { it.side == BattleSide.OPPONENT }.all {
                it.combatStats?.knowledge == BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE && it.knownMoveIds.isEmpty()
            })
            assertEquals(2, context.candidates.count { it.kind == BattleActionKind.SWITCH })
            assertTrue(context.candidates.filter { it.kind == BattleActionKind.SWITCH }.all { candidate ->
                context.state.pokemon.any { it.side == BattleSide.ALLY && it.battlePokemonId == candidate.switchPokemonId }
            })
            assertEquals(4, context.state.pokemon.map { it.battlePokemonId }.distinct().size,
                "Native 20-character nickname truncation must not merge UUID identities")
            val moveCandidates = context.candidates.filter { it.kind == BattleActionKind.USE_MOVE }
            assertTrue(moveCandidates.isNotEmpty())
            assertTrue(moveCandidates.all { it.moveDetails?.effects?.coverage == BattleMoveEffectCoverage.DECLARATIVE_PARTIAL },
                "Native candidates must carry the product's partial declarative effect facts")
            assertTrue(context.publicActionCatalog.entries.flatMap { it.moves }.all {
                it.details.effects?.coverage == BattleMoveEffectCoverage.DECLARATIVE_PARTIAL
            })
        }
        val observations = request(original, "p1").deepCopy()
        val opponentIdent = observations.getAsJsonArray("publicLog").map { it.asString }
            .first { it.startsWith("|switch|p2a:") }.split('|')[2]
        observations.getAsJsonArray("publicLog").apply {
            add("|-start|$opponentIdent|typechange|Water")
            add("|-boost|$opponentIdent|atk|2")
            add("|-damage|$opponentIdent|50/100 brn")
        }
        val observed = EmbeddedTeamInput.context(observations, UUID(0, 1), original["turn"].asInt, 0)
            .state.pokemon.single { it.side == BattleSide.OPPONENT }
        assertEquals(setOf("water"), observed.knownTypeIds)
        assertEquals(2, observed.statStages["attack"])
        assertEquals(0.5, observed.hpFraction)
        assertEquals("brn", observed.statusId)
        val sourceIdent = observations.getAsJsonArray("publicLog").map { it.asString }
            .first { it.startsWith("|switch|p1a:") }.split('|')[2]
        observations.getAsJsonArray("publicLog").apply {
            add("|move|$sourceIdent|Thunderbolt|$opponentIdent|[miss]")
            add("|-miss|$sourceIdent|$opponentIdent")
            add("|-immune|$opponentIdent|[from] ability: Levitate")
            add("|-activate|$opponentIdent|move: Substitute|[damage]")
        }
        val outcomeState = EmbeddedTeamInput.context(observations, UUID(0, 1), original["turn"].asInt, 0).state
        val misses = outcomeState.observedEvents.filter { it.moveOutcome?.kind == BattleMoveOutcomeKind.MISSED }
        assertEquals(1, misses.size)
        assertEquals("thunderbolt", misses.single().moveOutcome!!.moveId)
        val immunity = outcomeState.observedEvents.single { it.moveOutcome?.kind == BattleMoveOutcomeKind.IMMUNE }
        assertNull(immunity.actorPokemonId)
        assertNull(immunity.moveOutcome!!.moveId)
        assertNull(immunity.moveOutcome!!.publicEffectId)
        assertEquals(0.5, outcomeState.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction)
        assertTrue(outcomeState.observedEvents.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        val knownIds = outcomeState.pokemon.map { it.battlePokemonId }.toSet()
        assertTrue(outcomeState.observedEvents.all {
            (it.actorPokemonId == null || it.actorPokemonId in knownIds) && it.targetPokemonIds.all(knownIds::contains)
        })
        for (side in listOf("p1", "p2")) {
            val limited = request(original, side).deepCopy()
            limited.getAsJsonArray("publicLog").apply {
                add("|-start|$opponentIdent|Taunt")
                add("|-mustrecharge|$opponentIdent")
            }
            val limitedContext = EmbeddedTeamInput.context(limited, UUID(0, 1), original["turn"].asInt, 0)
            val targetSide = if (side == "p1") BattleSide.OPPONENT else BattleSide.ALLY
            val target = limitedContext.state.pokemon.single { it.side == targetSide && it.activeSlot == 0 }
            assertTrue(target.actionConstraints.taunted)
            assertTrue(target.actionConstraints.mustRecharge)
            assertEquals(request(original, side)["actions"], limited["actions"], "Public evidence must not rewrite native legality")
        }
        EmbeddedTeamBattle.NativeSession(directory.resolve("audit/engine"), pair, directory.resolve("invalid"), 2).use { native ->
            native.read()
            native.send(JsonObject().apply { add("choices", JsonObject().apply {
                addProperty("p1", "forfeit"); addProperty("p2", "move 1")
            }) })
            val failure = assertThrows(IllegalStateException::class.java) { native.read() }
            assertTrue(failure.message!!.contains("Action not exposed"))
        }
    }
}
