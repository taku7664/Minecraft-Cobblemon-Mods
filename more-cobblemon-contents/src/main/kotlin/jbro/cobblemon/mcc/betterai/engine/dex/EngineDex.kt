package jbro.cobblemon.mcc.betterai.engine.dex

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.util.zip.GZIPInputStream
import jbro.cobblemon.mcc.betterai.engine.Js

/**
 * The engine's copy of Showdown's data tables, loaded from the exported `ai-engine/dex.json.gz`
 * (see `tools/ai-engine/export-dex.cjs`). Lookups mirror `sim/dex*.js`, including where a condition
 * comes from when its id names a move, ability or item.
 */
class EngineDex private constructor(root: JsonObject) {
    val typeNames: List<String>
    private val damageTaken: Map<String, Map<String, Int>>
    private val natures: Map<String, Nature>
    private val speciesTable: Map<String, Species>
    private val moveTable: Map<String, MoveData>
    private val abilityTable: Map<String, Effect>
    private val itemTable: Map<String, Effect>
    private val conditionData: Map<String, JsonObject>
    private val conditionCache = HashMap<String, Effect>()

    init {
        val types = root.getAsJsonObject("typechart")
        typeNames = types.keySet().toList()
        damageTaken = types.entrySet().associate { (name, value) ->
            name to value.asJsonObject.getAsJsonObject("damageTaken").entrySet().associate { it.key to it.value.asInt }
        }
        natures = root.getAsJsonObject("natures").entrySet().associate { (id, value) ->
            val obj = value.asJsonObject
            id to Nature(id, obj.get("name").asString, obj.get("plus")?.takeIf { !it.isJsonNull }?.asString,
                obj.get("minus")?.takeIf { !it.isJsonNull }?.asString)
        }
        speciesTable = root.getAsJsonObject("species").entrySet().associate { (id, value) -> id to Species(value.asJsonObject) }
        moveTable = root.getAsJsonObject("moves").entrySet().associate { (id, value) -> id to MoveData(id, value.asJsonObject) }
        abilityTable = root.getAsJsonObject("abilities").entrySet().associate { (id, value) ->
            id to effect(id, value.asJsonObject, "Ability", "ability:$id", "")
        }
        itemTable = root.getAsJsonObject("items").entrySet().associate { (id, value) ->
            id to effect(id, value.asJsonObject, "Item", "item:$id", "")
        }
        conditionData = root.getAsJsonObject("conditions").entrySet().associate { (id, value) -> id to value.asJsonObject }
    }

    val emptyEffect: Effect = Effect("", "", "Condition", JsonObject(), "", emptySet())
    val emptyAbility: Effect = Effect("", "", "Ability", JsonObject(), "", emptySet())
    val emptyItem: Effect = Effect("", "", "Item", JsonObject(), "", emptySet())

    fun species(name: String): Species? = speciesTable[Js.toID(name)]

    fun move(name: String): MoveData? = moveTable[Js.toID(name)]

    fun ability(name: String): Effect = abilityTable[Js.toID(name)] ?: emptyAbility

    fun item(name: String): Effect = itemTable[Js.toID(name)] ?: emptyItem

    fun abilityOrNull(id: String): Effect? = abilityTable[id]

    fun itemOrNull(id: String): Effect? = itemTable[id]

    fun nature(name: String): Nature? = natures[Js.toID(name)]

    val allMoves: Collection<MoveData> get() = moveTable.values
    val allAbilities: Collection<Effect> get() = abilityTable.values
    val allItems: Collection<Effect> get() = itemTable.values
    val allSpecies: Collection<Species> get() = speciesTable.values
    val conditionIds: Set<String> get() = conditionData.keys

    fun isTypeName(name: String): Boolean = name in damageTaken

    /** `dex.conditions.get(name)`: ids are normalised unless they carry an `item:`/`ability:` prefix. */
    fun condition(name: String): Effect {
        if (name.isEmpty()) return emptyEffect
        val id = if (name.startsWith("item:") || name.startsWith("ability:")) name else Js.toID(name)
        return conditionById(id)
    }

    /** `dex.conditions.getByID(id)`. */
    fun conditionById(id: String): Effect {
        if (id.isEmpty()) return emptyEffect
        conditionCache[id]?.let { return it }
        val built = when {
            id.startsWith("item:") -> item(id.substring(5)).let { base ->
                Effect("item:${base.id}", base.name, "Item", base.raw, base.hookKey, base.declaredHooks)
            }
            id.startsWith("ability:") -> ability(id.substring(8)).let { base ->
                Effect("ability:${base.id}", base.name, "Ability", base.raw, base.hookKey, base.declaredHooks)
            }
            id in conditionData -> {
                val raw = conditionData.getValue(id)
                val type = raw.get("effectType")?.asString?.takeIf { it == "Weather" || it == "Status" } ?: "Condition"
                Effect(id, raw.get("name")?.asString ?: id, type, raw, "condition:$id", hooksOf(raw))
            }
            else -> nestedCondition(id) ?: when (id) {
                "recoil" -> Effect("recoil", "Recoil", "Recoil", JsonObject(), "", emptySet())
                "drain" -> Effect("drain", "Drain", "Drain", JsonObject(), "", emptySet())
                else -> Effect(id, id, "Condition", JsonObject().apply { addProperty("exists", false) }, "", emptySet())
            }
        }
        conditionCache[id] = built
        return built
    }

    private fun nestedCondition(id: String): Effect? {
        for ((owner, key) in listOf(moveTable[id] to "move:$id", abilityTable[id] to "ability:$id", itemTable[id] to "item:$id")) {
            if (owner == null) continue
            val condition = owner.raw.get("condition")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val hooks = owner.declaredHooks.filter { it.startsWith("condition.") }.map { it.removePrefix("condition.") }.toSet()
            val type = condition.get("effectType")?.asString?.takeIf { it == "Weather" || it == "Status" } ?: "Condition"
            return Effect(id, owner.name, type, condition, "$key/condition", hooks)
        }
        return null
    }

    /** Type effectiveness of an attacking type against one defending type: 1, -1 or 0. */
    fun effectiveness(sourceType: String, targetType: String): Int = when (damageTaken[targetType]?.get(sourceType)) {
        1 -> 1
        2 -> -1
        else -> 0
    }

    /** `dex.getImmunity` against one defending type or status id. */
    fun immune(sourceType: String, targetType: String): Boolean = damageTaken[targetType]?.get(sourceType) == 3

    fun notImmune(sourceType: String, targetTypes: List<String>): Boolean = targetTypes.none { immune(sourceType, it) }

    private fun effect(id: String, raw: JsonObject, type: String, key: String, prefix: String): Effect =
        Effect(id, raw.get("name")?.asString ?: id, raw.get("effectType")?.asString ?: type, raw, key, hooksOf(raw, prefix))

    companion object {
        const val RESOURCE = "/ai-engine/dex.json.gz"

        @Volatile
        private var shared: EngineDex? = null

        fun load(input: InputStream): EngineDex =
            GZIPInputStream(input).reader(Charsets.UTF_8).use { EngineDex(JsonParser.parseReader(it).asJsonObject) }

        /** The bundled dex, loaded once per JVM. */
        fun bundled(): EngineDex = shared ?: synchronized(this) {
            shared ?: load(requireNotNull(EngineDex::class.java.getResourceAsStream(RESOURCE)) {
                "AI engine dex resource $RESOURCE is missing"
            }).also { shared = it }
        }

        internal fun hooksOf(raw: JsonObject, prefix: String = ""): Set<String> {
            val hooks = raw.getAsJsonArray("hooks") ?: return emptySet()
            return hooks.mapNotNull { h -> h.asString.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) }.toSet()
        }
    }
}

class Nature(val id: String, val name: String, val plus: String?, val minus: String?)

/** A Pokédex entry (`sim/dex-species.js` Species). */
class Species(raw: JsonObject) : Effect(
    raw.get("id").asString, raw.get("name").asString, "Pokemon", raw, "", emptySet(),
) {
    val baseSpecies: String = string("baseSpecies") ?: name
    val forme: String = string("forme") ?: ""
    val types: List<String> = list("types")?.map { it as String } ?: listOf("???")
    val baseStats: Map<String, Int> = map("baseStats")?.mapValues { Js.int(it.value) } ?: emptyMap()
    val abilities: Map<String, String> = map("abilities")?.mapValues { it.value as String } ?: mapOf("0" to "")
    val weighthg: Int = int("weighthg") ?: 0
    val gender: String = string("gender") ?: ""
    val genderRatio: Map<String, Double> = map("genderRatio")?.mapValues { Js.num(it.value) } ?: mapOf("M" to 0.5, "F" to 0.5)
    val maxHP: Int? = int("maxHP")
    val isMega: Boolean = bool("isMega")
    val isPrimal: Boolean = bool("isPrimal")
    val requiredItem: String? = string("requiredItem")
    val battleOnly: Any? = data("battleOnly")
    val changesFrom: String? = string("changesFrom")
    val cannotDynamax: Boolean = bool("cannotDynamax")
    val canGigantamax: String? = string("canGigantamax")
    val nfe: Boolean = bool("nfe")
    val unreleasedHidden: Boolean = bool("unreleasedHidden")
    val addedType: String? = string("addedType")
    val forceTeraType: String? = string("forceTeraType")
    val otherFormes: List<String> = list("otherFormes")?.map { it as String } ?: emptyList()
}
