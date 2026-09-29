package jbro.cobblemon.mcc.betterai.engine

/**
 * JavaScript value rules the Showdown port depends on.
 *
 * Handlers return `Any?`: Kotlin `Unit` stands for JS `undefined` ("no change"), `null` for JS `null`
 * (silent failure), `false` for a loud failure, and numbers, strings, objects for replacement values.
 * Numbers are `Int` when integral and `Double` otherwise, like JS numbers that happen to be whole.
 */
object Js {
    val UNDEFINED: Unit = Unit

    fun isUndefined(value: Any?): Boolean = value === Unit

    fun truthy(value: Any?): Boolean = when (value) {
        null, Unit, false -> false
        is Int -> value != 0
        is Long -> value != 0L
        is Double -> value != 0.0 && !value.isNaN()
        is Float -> value != 0f && !value.isNaN()
        is String -> value.isNotEmpty()
        else -> true
    }

    fun isNumber(value: Any?): Boolean = value is Int || value is Double || value is Long || value is Float

    fun num(value: Any?): Double = when (value) {
        is Int -> value.toDouble()
        is Double -> value
        is Long -> value.toDouble()
        is Float -> value.toDouble()
        true -> 1.0
        false, null -> 0.0
        is String -> value.toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    /** A JS number normalised back to `Int` when it is whole and fits, as the port prefers. */
    fun number(value: Double): Any = if (value == Math.floor(value) && !value.isInfinite() &&
        value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else value

    fun int(value: Any?): Int = when (value) {
        is Int -> value
        else -> num(value).let { if (it.isNaN()) 0 else it.toInt() }
    }

    /** `typeof` for the result-combining rules of BattleActions#combineResults. */
    fun typeOf(value: Any?): String = when (value) {
        Unit -> "undefined"
        null -> "object"
        is Boolean -> "boolean"
        is Int, is Double, is Long, is Float -> "number"
        is String -> "string"
        else -> "object"
    }

    /** `String(value)` as Showdown's `parts.join('|')` would print it. */
    fun str(value: Any?): String = when (value) {
        null, Unit -> ""
        is Double -> if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e21) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Float -> str(value.toDouble())
        else -> value.toString()
    }

    /** `x >>> 0`, Showdown's `trunc`. */
    fun trunc(value: Double): Int {
        if (value.isNaN() || value.isInfinite()) return 0
        val unsigned = (value.toLong() and 0xFFFFFFFFL)
        return unsigned.toInt()
    }

    fun trunc(value: Double, bits: Int): Int {
        if (bits == 0) return trunc(value)
        val unsigned = if (value.isNaN() || value.isInfinite()) 0L else value.toLong() and 0xFFFFFFFFL
        return (unsigned % (1L shl bits)).toInt()
    }

    /** `Utils.clampIntRange`: non-numbers become 0, the rest is floored, then clamped. */
    fun clampIntRange(num: Any?, min: Int? = null, max: Int? = null): Int {
        var value = if (isNumber(num)) Math.floor(num(num)) else 0.0
        if (min != null && value < min) value = min.toDouble()
        if (max != null && value > max) value = max.toDouble()
        return value.toInt()
    }

    fun toID(text: Any?): String {
        val raw = when (text) {
            null, Unit -> return ""
            is String -> text
            is Int, is Double -> str(text)
            is HasId -> text.id
            else -> return ""
        }
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            val lower = ch.lowercaseChar()
            if (lower in 'a'..'z' || lower in '0'..'9') out.append(lower)
        }
        return out.toString()
    }
}

interface HasId {
    val id: String
}
