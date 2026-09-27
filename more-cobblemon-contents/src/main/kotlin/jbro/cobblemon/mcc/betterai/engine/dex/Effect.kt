package jbro.cobblemon.mcc.betterai.engine.dex

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import jbro.cobblemon.mcc.betterai.engine.HasId
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.hooks.EngineHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookFn

/**
 * Anything Showdown treats as an effect: it has an id, a name, an effect type, data fields, and may carry
 * event handlers. Handlers come from two places: constant values in the data (`onLockMove: "recharge"`)
 * and Kotlin code registered in [EngineHooks] under this effect's hook key.
 */
interface EffectLike : HasId {
    val name: String
    val fullname: String
    val effectType: String
    val num: Int
    val hookKey: String

    /** The handler for `onX` (or a callback such as `basePowerCallback`): a [HookFn], a constant, or `null`. */
    fun handler(callbackName: String): Any?

    /** Whether Showdown's data declares this handler, implemented here or not. */
    fun declares(callbackName: String): Boolean

    /** A raw data field, converted to Kotlin values (`Int`/`Double`, `String`, `Boolean`, maps, lists). */
    fun data(field: String): Any?

    fun number(field: String): Double? = data(field)?.let { if (Js.isNumber(it)) Js.num(it) else null }

    fun flag(name: String): Boolean = false

    val exists: Boolean get() = id.isNotEmpty()
}

/** Converts a Gson element to the plain Kotlin values the port works with. */
object JsonValues {
    fun convert(element: JsonElement?): Any? = when {
        element == null || element is JsonNull -> null
        element is JsonPrimitive && element.isBoolean -> element.asBoolean
        element is JsonPrimitive && element.isNumber -> Js.number(element.asDouble)
        element is JsonPrimitive -> element.asString
        element is JsonArray -> element.map { convert(it) }
        element is JsonObject -> LinkedHashMap<String, Any?>().also { map ->
            for ((key, value) in element.entrySet()) map[key] = convert(value)
        }
        else -> null
    }
}

/** A dex entry: move template, ability, item, condition, species, or an effect built from part of one. */
open class Effect(
    override val id: String,
    override val name: String,
    override val effectType: String,
    val raw: JsonObject,
    override val hookKey: String,
    /** Handler names the data declares as functions, e.g. `onStart`, `durationCallback`. */
    val declaredHooks: Set<String>,
) : EffectLike {
    private val converted = HashMap<String, Any?>()
    private val handlerCache = HashMap<String, Any?>()

    override val fullname: String = when (effectType) {
        "Move" -> "move: $name"
        "Ability" -> "ability: $name"
        "Item" -> "item: $name"
        "Pokemon" -> "pokemon: $name"
        else -> name
    }

    override val num: Int = raw.get("num")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    override fun data(field: String): Any? {
        if (converted.containsKey(field)) return converted[field]
        val value = JsonValues.convert(raw.get(field))
        converted[field] = value
        return value
    }

    fun has(field: String): Boolean = raw.has(field) && !raw.get(field).isJsonNull

    fun string(field: String): String? = data(field) as? String

    fun bool(field: String): Boolean = Js.truthy(data(field))

    fun int(field: String): Int? = data(field)?.let { if (Js.isNumber(it)) Js.int(it) else null }

    @Suppress("UNCHECKED_CAST")
    fun map(field: String): Map<String, Any?>? = data(field) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(field: String): List<Any?>? = data(field) as? List<Any?>

    val flags: Map<String, Any?> by lazy { map("flags") ?: emptyMap() }

    override fun flag(name: String): Boolean = Js.truthy(flags[name])

    val duration: Int? get() = int("duration")

    override fun declares(callbackName: String): Boolean =
        callbackName in declaredHooks || (raw.has(callbackName) && !isPriorityField(callbackName))

    override fun handler(callbackName: String): Any? {
        if (handlerCache.containsKey(callbackName)) return handlerCache[callbackName]
        val resolved: Any? = when {
            callbackName in declaredHooks -> EngineHooks.get(hookKey, callbackName) ?: MissingHook(hookKey, callbackName)
            raw.has(callbackName) && !isPriorityField(callbackName) -> JsonValues.convert(raw.get(callbackName))
            else -> EngineHooks.get(hookKey, callbackName)
        }
        handlerCache[callbackName] = resolved
        return resolved
    }

    override fun toString(): String = name

    companion object {
        fun isPriorityField(key: String): Boolean =
            key.endsWith("Priority") || key.endsWith("Order") || key.endsWith("SubOrder")
    }
}

/**
 * A handler Showdown has in code but the engine does not implement yet. Calling it records the gap on the
 * battle and behaves like a missing handler, so an unported effect shows up in reports instead of silently
 * acting like Showdown.
 */
class MissingHook(val hookKey: String, val callbackName: String) {
    override fun toString(): String = "$hookKey.$callbackName"
}

/** A Kotlin handler implementation. */
fun HookFn.asHandler(): Any = this
