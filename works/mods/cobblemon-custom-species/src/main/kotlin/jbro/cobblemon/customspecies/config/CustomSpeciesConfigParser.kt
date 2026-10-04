package jbro.cobblemon.customspecies.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class CustomSpeciesConfigParser {
    fun parse(source: String): CustomSpeciesConfig {
        val root = try {
            JsonParser.parseString(source).requireObject("root")
        } catch (error: ConfigValidationException) {
            throw error
        } catch (error: Exception) {
            throw ConfigValidationException("Invalid JSON: ${error.message}", error)
        }
        root.requireOnly("root", setOf("schema", "overrides"))
        val schema = root.requireInt("schema")
        if (schema != 1) fail("schema must be 1, got $schema")
        val overrides = root.requireArray("overrides").mapIndexed { index, element ->
            parseOverride(element.requireObject("overrides[$index]"), index)
        }
        val duplicates = overrides.groupBy { it.species to selectorId(it.form) }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            fail("Duplicate override targets: ${duplicates.joinToString { "${it.first}#${it.second}" }}")
        }
        return CustomSpeciesConfig(schema, overrides)
    }

    private fun parseOverride(value: JsonObject, index: Int): SpeciesOverride {
        val path = "overrides[$index]"
        value.requireOnly(path, setOf("species", "form", "base_stats", "abilities", "moves"))
        val species = value.requireString("species").lowercase()
        if (!RESOURCE_ID.matches(species)) fail("$path.species must be a namespaced lowercase identifier")
        val form = parseForm(value.get("form"), "$path.form")
        val stats = value.get("base_stats")?.let { parseStats(it.requireObject("$path.base_stats"), "$path.base_stats") } ?: emptyMap()
        val abilities = value.get("abilities")?.let { parseAbilities(it.requireObject("$path.abilities"), "$path.abilities") } ?: AbilityOverride()
        val moves = value.get("moves")?.let { parseMoves(it.requireObject("$path.moves"), "$path.moves") } ?: MoveOverride()
        if (stats.isEmpty() && abilities == AbilityOverride() && moves == MoveOverride()) {
            fail("$path must contain at least one operation")
        }
        return SpeciesOverride(species, form, stats, abilities, moves)
    }

    private fun parseForm(element: JsonElement?, path: String): FormSelector {
        if (element == null) return FormSelector.Base
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) fail("$path must be a string")
        return when (val form = element.asString.lowercase()) {
            "base" -> FormSelector.Base
            "*" -> FormSelector.All
            else -> {
                if (!FORM_ID.matches(form)) fail("$path must be base, *, or a lowercase form id")
                FormSelector.Named(form)
            }
        }
    }

    private fun parseStats(value: JsonObject, path: String): Map<StatKey, Int> {
        val result = linkedMapOf<StatKey, Int>()
        for ((name, element) in value.entrySet()) {
            val stat = StatKey.fromJsonName(name) ?: fail("Unknown field $path.$name")
            val amount = element.asStrictInt("$path.$name")
            if (amount !in 1..999) fail("$path.$name must be between 1 and 999")
            result[stat] = amount
        }
        return result
    }

    private fun parseAbilities(value: JsonObject, path: String): AbilityOverride {
        value.requireOnly(path, setOf("add", "remove", "replace"))
        val add = value.optionalStringList("add", path)
        val remove = value.optionalStringList("remove", path)
        val replace = value.get("replace")?.let { value.stringList("replace", path) }
        if (replace != null && (add.isNotEmpty() || remove.isNotEmpty())) {
            fail("$path.replace cannot be combined with add or remove")
        }
        if (replace != null && replace.isEmpty()) fail("$path.replace cannot be empty")
        return AbilityOverride(add, remove, replace)
    }

    private fun parseMoves(value: JsonObject, path: String): MoveOverride {
        value.requireOnly(path, setOf("add", "remove", "remove_moves"))
        return MoveOverride(
            add = value.optionalStringList("add", path),
            remove = value.optionalStringList("remove", path),
            removeMoves = value.optionalStringList("remove_moves", path)
        )
    }

    private fun selectorId(selector: FormSelector): String = when (selector) {
        FormSelector.Base -> "base"
        FormSelector.All -> "*"
        is FormSelector.Named -> selector.id
    }

    private fun JsonObject.requireOnly(path: String, allowed: Set<String>) {
        val unknown = keySet() - allowed
        if (unknown.isNotEmpty()) fail("Unknown field(s) in $path: ${unknown.sorted().joinToString()}")
    }

    private fun JsonObject.requireInt(name: String): Int = get(name)?.asStrictInt(name) ?: fail("Missing $name")

    private fun JsonElement.asStrictInt(path: String): Int {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) fail("$path must be an integer")
        return try {
            asBigDecimal.toBigIntegerExact().intValueExact()
        } catch (_: Exception) {
            fail("$path must be an integer")
        }
    }

    private fun JsonObject.requireString(name: String): String {
        val element = get(name) ?: fail("Missing $name")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString || element.asString.isBlank()) fail("$name must be a non-empty string")
        return element.asString
    }

    private fun JsonObject.requireArray(name: String): JsonArray = get(name)?.let {
        if (!it.isJsonArray) fail("$name must be an array")
        it.asJsonArray
    } ?: fail("Missing $name")

    private fun JsonObject.optionalStringList(name: String, path: String): List<String> =
        if (has(name)) stringList(name, path) else emptyList()

    private fun JsonObject.stringList(name: String, path: String): List<String> {
        val array = requireArray(name)
        val values = array.mapIndexed { index, item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString || item.asString.isBlank()) {
                fail("$path.$name[$index] must be a non-empty string")
            }
            item.asString.lowercase()
        }
        if (values.size != values.distinct().size) fail("$path.$name contains duplicates")
        return values
    }

    private fun JsonElement.requireObject(path: String): JsonObject {
        if (!isJsonObject) fail("$path must be an object")
        return asJsonObject
    }

    private fun fail(message: String): Nothing = throw ConfigValidationException(message)

    private companion object {
        val RESOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        val FORM_ID = Regex("[a-z0-9_-]+")
    }
}
