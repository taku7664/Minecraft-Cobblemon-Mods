package jbro.cobblemon.mcc.betterai.engine.dex

import com.google.gson.JsonObject
import jbro.cobblemon.mcc.betterai.engine.Js

/** A move's Showdown data (`sim/dex-moves.js` DataMove), the template every [ActiveMove][jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove] copies. */
class MoveData(id: String, raw: JsonObject, hookKey: String = "move:$id") : Effect(
    id, raw.get("name")?.asString ?: id, "Move", raw, hookKey, EngineDex.hooksOf(raw),
) {
    val type: String = string("type") ?: "???"
    val category: String = string("category") ?: "Physical"
    val basePower: Int = int("basePower") ?: 0
    val accuracy: Any = data("accuracy") ?: true
    val pp: Int = int("pp") ?: 0
    val priority: Int = int("priority") ?: 0
    val target: String = string("target") ?: "normal"
    val critRatio: Int = int("critRatio") ?: 1
    val noPPBoosts: Boolean = bool("noPPBoosts")
    val isZ: Any? = data("isZ")
    val isMax: Any? = data("isMax")
    val nonGhostTarget: String = string("nonGhostTarget") ?: ""
    val sleepUsable: Boolean = bool("sleepUsable")

    /** Secondary effects in data order (`secondaries`, falling back to `secondary`). */
    val secondaryEffects: List<HitEffect>? by lazy {
        val list = raw.get("secondaries")?.takeIf { it.isJsonArray }?.asJsonArray
        when {
            list != null -> list.mapIndexed { i, e -> HitEffect(this, e.asJsonObject, "secondaries.$i") }
            raw.get("secondary")?.isJsonObject == true -> listOf(HitEffect(this, raw.getAsJsonObject("secondary"), "secondary"))
            else -> null
        }
    }

    val selfEffect: HitEffect? by lazy {
        raw.get("self")?.takeIf { it.isJsonObject }?.let { HitEffect(this, it.asJsonObject, "self") }
    }
}

/**
 * Part of a move applied as its own hit: a secondary effect, or the move's `self` block. Showdown passes
 * these to `spreadMoveHit` as `moveData`, and runs their own `onHit` handlers.
 */
class HitEffect(val move: MoveData, raw: JsonObject, path: String) : Effect(
    move.id, move.name, "", raw, "${move.hookKey}/$path",
    move.declaredHooks.filter { it.startsWith("$path.") }.map { it.removePrefix("$path.") }.toSet(),
) {
    val chance: Int? = int("chance")

    @Suppress("UNCHECKED_CAST")
    val boosts: Map<String, Int>? = map("boosts")?.mapValues { Js.int(it.value) }
    val status: String? = string("status")
    val volatileStatus: String? = string("volatileStatus")
    val sideCondition: String? = string("sideCondition")
    val slotCondition: String? = string("slotCondition")
    val weather: String? = string("weather")
    val terrain: String? = string("terrain")
    val pseudoWeather: String? = string("pseudoWeather")

    val self: HitEffect? by lazy {
        raw.get("self")?.takeIf { it.isJsonObject }?.let { HitEffect(move, it.asJsonObject, "${path()}.self") }
    }

    private fun path(): String = hookKey.substringAfter('/')

    override val fullname: String get() = move.fullname
}
