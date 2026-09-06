package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/** Test referee point stats are synthetic public inputs, never observations of a live opponent. */
internal object EmbeddedTurnOrderDifferential {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val result = compare(directory)
        result.add("provenance", GsonBuilder().create().toJsonTree(
            LocalBaselineProvenance.capture(Path.of("").toAbsolutePath().normalize())))
        Files.writeString(directory.resolve("comparison.json"),
            GsonBuilder().setPrettyPrinting().create().toJson(result), CREATE_NEW)
        println("turn order comparison cases=${result.getAsJsonArray("cases").size()} mismatches=${result["mismatchCount"]}")
        check(result["mismatchCount"].asInt == 0)
    }

    fun compare(directory: Path): JsonObject {
        val oracle = EmbeddedShowdownOracle.turnOrder(directory)
        val mismatches = JsonArray()
        val rows = JsonArray()
        for (entry in oracle.getAsJsonArray("cases")) {
            val case = entry.asJsonObject
            val id = case["id"].asString
            val input = case.getAsJsonObject("input")
            val own = input.getAsJsonObject("ally")
            val foe = input.getAsJsonObject("opponent")
            val moves = listOf(move(own, BattleSide.ALLY), move(foe, BattleSide.OPPONENT))
            val state = BattleStateView(UUID.nameUUIDFromBytes(id.toByteArray()), BattleFormat.SINGLE, 1,
                listOf(pokemon(own, BattleSide.ALLY), pokemon(foe, BattleSide.OPPONENT)),
                BattleFieldStateView(null, null,
                    if (input["trickRoom"].asBoolean) listOf(BattleTimedEffectView("trickroom", 3)) else emptyList(),
                    emptyList(), BattleSide.entries.associateWith { side ->
                        if (side == BattleSide.ALLY && input["allyTailwind"].asBoolean)
                            listOf(BattleTimedEffectView("tailwind", 3)) else emptyList()
                    }),
                BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
            val source = PublicBattleTacticalCalculator.calculate(BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("request:$id".toByteArray()), state = state,
                candidates = listOf(moves[0]), deadlineEpochMillis = Long.MAX_VALUE))
            val first = if (case["expectedFirstSide"].asString == "p1") BattleSide.ALLY else BattleSide.OPPONENT
            val expectedProbability = if (first == BattleSide.ALLY) 1.0 else 0.0
            val probability = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, 0,
                own["priority"].asInt, foe["priority"].asInt)
            val projections = PublicSingleTurnProjector.project(state, moves[0], moves[1], source)
            fun checkCase(ok: Boolean, kind: String) {
                if (!ok) mismatches.add("$id:$kind")
            }
            val native = case.getAsJsonObject("result")
            checkCase(native["firstSide"].asString == case["expectedFirstSide"].asString, "NATIVE_ORDER")
            checkCase(native["moveCount"].asInt == 1, "NATIVE_CANCELLATION")
            checkCase(native.getAsJsonArray("faintedSides").map { it.asString } ==
                listOf(if (first == BattleSide.ALLY) "p2" else "p1"), "NATIVE_KO")
            checkCase(probability == expectedProbability, "PUBLIC_ORDER_PROBABILITY")
            checkCase(projections.isNotEmpty(), "EMPTY_PROJECTION")
            checkCase(projections.all { it.order.firstOrNull() == first && it.orderProbability == 1.0 }, "PROJECTED_ORDER")
            checkCase(projections.all { it.executedSides == setOf(first) }, "PROJECTED_CANCELLATION")
            checkCase(projections.all { p -> p.state.pokemon.all {
                if (it.side == first) !it.fainted && it.hpFraction > 0.0 else it.fainted && it.hpFraction == 0.0
            } }, "PROJECTED_KO")
            rows.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("actsFirstProbability", probability)
                addProperty("projectionBranches", projections.size)
                add("nativeCase", case)
            })
        }
        return JsonObject().apply {
            addProperty("scope", "SCRIPTED_LETHAL_ORDER_AND_CANCELLATION_NOT_AI_QUALITY")
            addProperty("mismatchCount", mismatches.size())
            add("mismatches", mismatches)
            add("cases", rows)
            add("refereeOracle", oracle)
        }
    }

    private fun pokemon(input: JsonObject, side: BattleSide): BattlePokemonStateView {
        fun stat(name: String) = input[name].asInt.let { BattleIntegerRange(it, it) }
        return BattlePokemonStateView(battlePokemonId = UUID(0, if (side == BattleSide.ALLY) 1 else 2),
            side = side, activeSlot = 0, speciesId = input["speciesId"].asString, formId = null, level = 50,
            hpFraction = 1.0 / input["maxHp"].asInt, statusId = null,
            statStages = mapOf("speed" to input["speedStage"].asInt),
            knownMoveIds = setOf("cobblemon:" + input["moveId"].asString),
            knownAbilityId = null, knownHeldItemId = null, fainted = false,
            knownTypeIds = if (input["speciesId"].asString.endsWith("pikachu")) setOf("electric") else setOf("normal"),
            combatStats = BattleCombatStatRangesView(stat("maxHp"), stat("attack"), stat("defence"),
                stat("specialAttack"), stat("specialDefence"), stat("speed"),
                if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
    }

    private fun move(input: JsonObject, side: BattleSide) = BattleActionCandidate(
        actionId = side.name, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:" + input["moveId"].asString,
        targets = listOf(BattleTargetSlot(if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY, 0)),
        moveDetails = BattleMoveCandidateView(typeId = "normal", power = input["power"].asDouble,
            accuracy = 100.0, damageCategory = BattleMoveDamageCategory.PHYSICAL,
            priority = input["priority"].asInt, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
}
