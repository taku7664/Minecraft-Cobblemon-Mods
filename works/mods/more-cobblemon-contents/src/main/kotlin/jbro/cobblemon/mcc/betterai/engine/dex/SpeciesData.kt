package jbro.cobblemon.mcc.betterai.engine.dex

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import jbro.cobblemon.mcc.betterai.engine.Js

/**
 * Showdown's `new Species(data)` (`sim/dex-species.js`, with `BasicEffect` from `sim/dex-data.js`). Cobblemon
 * hands Showdown every species as plain data (`receiveSpeciesData`) and Showdown builds them this way, so
 * the engine builds the same fields from the same data. The result has the shape `JSON.stringify` gives a
 * Showdown Species: undefined fields are left out.
 */
object ShowdownSpeciesData {
    fun construct(data: JsonObject): JsonObject {
        val o = JsonObject()
        // BasicEffect: `this.exists = true; Object.assign(this, data)`.
        o.addProperty("exists", true)
        for ((key, value) in data.entrySet()) o.add(key, value.deepCopy())
        val name = (data.string("name") ?: "").trim()
        o.addProperty("name", name)
        val id = data.string("realMove")?.let { Js.toID(it) } ?: Js.toID(name)
        o.addProperty("id", id)
        o.addProperty("effectType", "Pokemon")
        o.addProperty("exists", o.truthy("exists") && id.isNotEmpty())
        o.add("num", data.orElse("num", JsonPrimitive(0)))
        o.add("gen", data.orElse("gen", JsonPrimitive(0)))
        o.add("shortDesc", data.orElse("shortDesc", JsonPrimitive("")))
        o.add("desc", data.orElse("desc", JsonPrimitive("")))
        o.add("isNonstandard", data.orElse("isNonstandard", JsonNull.INSTANCE))
        o.setOrRemove("duration", data.get("duration")?.deepCopy())
        o.addProperty("noCopy", data.truthy("noCopy"))
        o.addProperty("affectsFainted", data.truthy("affectsFainted"))
        o.setOrRemove("status", data.orNull("status"))
        o.setOrRemove("weather", data.orNull("weather"))
        o.add("sourceEffect", data.orElse("sourceEffect", JsonPrimitive("")))

        // Species: from here on `data` is the object itself, so later fields read what earlier ones set.
        o.addProperty("fullname", "pokemon: $name")
        o.addProperty("effectType", "Pokemon")
        o.add("baseSpecies", o.orElse("baseSpecies", JsonPrimitive(name)))
        val baseSpecies = o.string("baseSpecies")!!
        o.add("forme", o.orElse("forme", JsonPrimitive("")))
        val forme = o.string("forme")!!
        o.add("baseForme", o.orElse("baseForme", JsonPrimitive("")))
        o.setOrRemove("cosmeticFormes", o.orNull("cosmeticFormes"))
        o.setOrRemove("otherFormes", o.orNull("otherFormes"))
        o.setOrRemove("formeOrder", o.orNull("formeOrder"))
        o.add("spriteid", o.orElse("spriteid", JsonPrimitive(Js.toID(baseSpecies) + if (baseSpecies != name) "-" + Js.toID(forme) else "")))
        o.add("abilities", o.orElse("abilities", JsonObject().apply { addProperty("0", "") }))
        o.add("types", o.orElse("types", JsonArray().apply { add("???") }))
        o.setOrRemove("addedType", o.orNull("addedType"))
        o.add("prevo", o.orElse("prevo", JsonPrimitive("")))
        o.add("tier", o.orElse("tier", JsonPrimitive("")))
        o.add("doublesTier", o.orElse("doublesTier", JsonPrimitive("")))
        o.add("natDexTier", o.orElse("natDexTier", JsonPrimitive("")))
        o.add("evos", o.orElse("evos", JsonArray()))
        o.setOrRemove("evoType", o.orNull("evoType"))
        o.setOrRemove("evoMove", o.orNull("evoMove"))
        o.setOrRemove("evoLevel", o.orNull("evoLevel"))
        o.add("nfe", o.orElse("nfe", JsonPrimitive(false)))
        o.add("eggGroups", o.orElse("eggGroups", JsonArray()))
        o.add("canHatch", o.orElse("canHatch", JsonPrimitive(false)))
        o.add("gender", o.orElse("gender", JsonPrimitive("")))
        val gender = o.string("gender")
        o.add("genderRatio", o.orElse("genderRatio", when (gender) {
            "M" -> ratio(1, 0)
            "F" -> ratio(0, 1)
            "N" -> ratio(0, 0)
            else -> JsonObject().apply { addProperty("M", 0.5); addProperty("F", 0.5) }
        }))
        o.setOrRemove("requiredItem", o.orNull("requiredItem"))
        o.setOrRemove("requiredItems", o.orNull("requiredItems")
            ?: o.orNull("requiredItem")?.let { item -> JsonArray().apply { add(item) } })
        o.add("baseStats", o.orElse("baseStats", JsonObject().apply { listOf("hp", "atk", "def", "spa", "spd", "spe").forEach { addProperty(it, 0) } }))
        val stats = o.getAsJsonObject("baseStats")
        o.add("bst", number(listOf("hp", "atk", "def", "spa", "spd", "spe").sumOf { stats.get(it)?.takeIf { v -> !v.isJsonNull }?.asDouble ?: Double.NaN }))
        o.add("weightkg", o.orElse("weightkg", JsonPrimitive(0)))
        o.add("weighthg", number(o.get("weightkg").asDouble * 10))
        o.add("heightm", o.orElse("heightm", JsonPrimitive(0)))
        o.add("color", o.orElse("color", JsonPrimitive("")))
        o.add("tags", o.orElse("tags", JsonArray()))
        o.add("unreleasedHidden", o.orElse("unreleasedHidden", JsonPrimitive(false)))
        o.addProperty("maleOnlyHidden", o.truthy("maleOnlyHidden"))
        o.setOrRemove("maxHP", o.orNull("maxHP"))
        val isMega = forme.isNotEmpty() && forme in listOf("Mega", "Mega-X", "Mega-Y")
        o.setOrRemove("isMega", if (isMega) JsonPrimitive(true) else null)
        o.setOrRemove("canGigantamax", o.orNull("canGigantamax"))
        o.addProperty("gmaxUnreleased", o.truthy("gmaxUnreleased"))
        o.addProperty("cannotDynamax", o.truthy("cannotDynamax"))
        o.setOrRemove("battleOnly", o.orNull("battleOnly") ?: if (isMega) JsonPrimitive(baseSpecies) else null)
        val battleOnly = o.get("battleOnly")
        o.setOrRemove("changesFrom", o.orNull("changesFrom")
            ?: if (battleOnly?.takeIf { it.isJsonPrimitive }?.asString != baseSpecies) battleOnly else JsonPrimitive(baseSpecies))
        o.setOrRemove("pokemonGoData", o.orNull("pokemonGoData"))
        (o.get("changesFrom") as? JsonArray)?.let { o.setOrRemove("changesFrom", it.firstOrNull()) }
        val num = o.get("num").asDouble
        if (!o.truthy("gen") && num >= 1) {
            val gen = when {
                num >= 906 || "Paldea" in forme -> 9
                num >= 810 || forme in listOf("Gmax", "Galar", "Galar-Zen", "Hisui") -> 8
                num >= 722 || forme.startsWith("Alola") || forme == "Starter" -> 7
                forme == "Primal" -> {
                    o.addProperty("isPrimal", true)
                    o.addProperty("battleOnly", baseSpecies)
                    6
                }
                num >= 650 || isMega -> 6
                num >= 494 -> 5
                num >= 387 -> 4
                num >= 252 -> 3
                num >= 152 -> 2
                else -> 1
            }
            o.addProperty("gen", gen)
        }
        return o
    }

    private fun ratio(m: Int, f: Int) = JsonObject().apply { addProperty("M", m); addProperty("F", f) }

    /** A JavaScript number as JSON: whole values without a fraction, as `JSON.stringify` writes them. */
    private fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonNull.INSTANCE
        value == Math.rint(value) && kotlin.math.abs(value) < 1e15 -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun truthy(value: JsonElement?): Boolean = when {
        value == null || value.isJsonNull -> false
        value.isJsonPrimitive -> value.asJsonPrimitive.let { p ->
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble.let { it != 0.0 && !it.isNaN() }
                else -> p.asString.isNotEmpty()
            }
        }
        else -> true
    }

    private fun JsonObject.truthy(key: String) = truthy(get(key))

    /** `data.key || fallback`. */
    private fun JsonObject.orElse(key: String, fallback: JsonElement): JsonElement = get(key)?.takeIf { truthy(it) }?.deepCopy() ?: fallback

    /** `data.key || undefined`. */
    private fun JsonObject.orNull(key: String): JsonElement? = get(key)?.takeIf { truthy(it) }?.deepCopy()

    private fun JsonObject.setOrRemove(key: String, value: JsonElement?) {
        if (value == null) remove(key) else add(key, value)
    }
}
