package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.betterai.mechanics.ShowdownStandardDamageProjection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Exact hidden stats are used only by this synthetic test referee, never by the product brain. */
internal object EmbeddedDamageDifferential {
    fun compare(directory: Path): JsonObject {
        val oracle = EmbeddedShowdownOracle.damage(directory)
        val mismatches = JsonArray()
        var thresholds = 0
        for (entry in oracle.getAsJsonArray("cases")) {
            val case = entry.asJsonObject
            val input = case.getAsJsonObject("input")
            val maxHp = input["maxHp"].asInt
            val expected = case.getAsJsonArray("rolls").map { it.asInt }
            fun projection(hp: Int) = ShowdownStandardDamageProjection.project(
                level = input["level"].asInt, power = input["power"].asInt,
                attack = input["attack"].asInt.let { BattleIntegerRange(it, it) },
                defence = input["defence"].asInt.let { BattleIntegerRange(it, it) },
                targetMaxHp = BattleIntegerRange(maxHp, maxHp), targetHpFraction = hp.toDouble() / maxHp,
                stab = input["stab"].asDouble, typeMultiplier = input["typeMultiplier"].asDouble,
            )
            val actual = projection(maxHp).minimumHypothesisRolls
            if (expected != actual) mismatches.add(JsonObject().apply {
                addProperty("case", case["id"].asString)
                addProperty("kind", "DAMAGE_ROLLS")
                addProperty("expected", expected.toString())
                addProperty("actual", actual.toString())
            })
            // Check every integer remaining HP, including thresholds vulnerable to float round trips.
            for (hp in 1..maxHp) {
                thresholds++
                val expectedKo = expected.count { it >= hp }.toDouble() / 16
                val actualKo = projection(hp).koProbabilityRange
                if (expectedKo != actualKo.minimum || expectedKo != actualKo.maximum) mismatches.add(JsonObject().apply {
                    addProperty("case", case["id"].asString)
                    addProperty("kind", "KNOCKOUT_THRESHOLD")
                    addProperty("hp", hp)
                    addProperty("maxHp", maxHp)
                    addProperty("fraction", hp.toDouble() / maxHp)
                    addProperty("expected", expectedKo)
                    addProperty("actualMinimum", actualKo.minimum)
                    addProperty("actualMaximum", actualKo.maximum)
                })
            }
        }
        return JsonObject().apply {
            val projectionClass = ShowdownStandardDamageProjection::class.java
            val projectionBytes = requireNotNull(projectionClass.getResourceAsStream(
                "/${projectionClass.name.replace('.', '/')}.class")).use { it.readBytes() }
            addProperty("projectionClassSha256", LocalBaselineCapture.digest(projectionBytes))
            addProperty("status", if (mismatches.isEmpty) "MATCH" else "MISMATCH")
            addProperty("cases", oracle.getAsJsonArray("cases").size())
            addProperty("koThresholdChecks", thresholds)
            addProperty("mismatchCount", mismatches.size())
            add("mismatches", mismatches)
            add("refereeOracle", oracle)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val result = compare(directory)
        Files.writeString(directory.resolve("comparison.json"), GsonBuilder().setPrettyPrinting().create().toJson(result), CREATE_NEW)
        println("damage comparison cases=${result["cases"]} thresholds=${result["koThresholdChecks"]} mismatches=${result["mismatchCount"]}")
        check(result["mismatchCount"].asInt == 0) { "Damage mismatch; inspect comparison.json" }
    }
}
