package jbro.cobblemon.mcc.betterai.engine.hooks

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Side

/** A ported Showdown handler. Returning `Unit` means JS `undefined`: leave the event's value alone. */
typealias HookFn = HookCall.() -> Any?

/**
 * What a Showdown handler sees: `this` (the battle, its `effect` and `effectState`) and the positional
 * arguments `(relayVar?, target, source, effect)`. For callbacks called as `fn(a, b, c)` the three
 * arguments land in [target], [source] and [effect].
 */
class HookCall(
    @JvmField val battle: Battle,
    @JvmField val relay: Any?,
    @JvmField val target: Any?,
    @JvmField val source: Any?,
    @JvmField val effect: Any?,
    @JvmField val state: EffectState,
    @JvmField val self: EffectLike,
) {
    val pokemon: Pokemon get() = target as Pokemon
    val targetMon: Pokemon? get() = target as? Pokemon
    val sourceMon: Pokemon? get() = source as? Pokemon
    val sourceSide: Side? get() = source as? Side
    val move: ActiveMove get() = effect as ActiveMove
    val activeMove: ActiveMove? get() = effect as? ActiveMove
    val sourceEffect: EffectLike? get() = effect as? EffectLike
    val relayInt: Int get() = Js.int(relay)
    val relayNum: Double get() = Js.num(relay)
    val dex get() = battle.dex
    val field get() = battle.field

    // Frequently used battle calls, so ported bodies read like the JS they came from.
    fun add(vararg parts: Any?) = battle.add(*parts)
    fun chainModify(numerator: Number, denominator: Number = 1) = battle.chainModify(numerator, denominator)
    fun chainModify(fraction: IntArray) = battle.chainModify(fraction[0], fraction[1])
    fun modify(value: Int, numerator: Number, denominator: Number = 1) = battle.modify(value, numerator, denominator)
    fun random(n: Int) = battle.random(n)
    fun random(m: Int, n: Int) = battle.random(m, n)
    fun randomChance(numerator: Int, denominator: Int) = battle.randomChance(numerator, denominator)
    fun damage(amount: Number, target: Pokemon? = null, source: Pokemon? = null, effect: EffectLike? = null) =
        battle.damage(amount, target, source, effect)
    fun heal(amount: Number, target: Pokemon? = null, source: Pokemon? = null, effect: EffectLike? = null) =
        battle.heal(amount, target, source, effect)
    fun boost(boosts: Map<String, Int>, target: Pokemon? = null, source: Pokemon? = null, effect: EffectLike? = null) =
        battle.boost(boosts, target, source, effect)
    fun debug(@Suppress("UNUSED_PARAMETER") message: String) = Unit
    fun hint(message: String) = battle.hint(message)
}

/**
 * Where ported handlers live, keyed like the exported dex's hook list: `condition:par`, `move:protect`,
 * `move:protect/condition`, `move:nuzzle/secondary`, `ability:intimidate`, `item:leftovers`.
 */
object EngineHooks {
    private val table = HashMap<String, HashMap<String, HookFn>>()

    @Volatile
    private var loaded = false

    fun get(key: String, callbackName: String): HookFn? {
        ensureLoaded()
        return table[key]?.get(callbackName)
    }

    fun implemented(key: String): Set<String> {
        ensureLoaded()
        return table[key]?.keys ?: emptySet()
    }

    val keys: Set<String> get() = ensureLoaded().let { table.keys }

    internal fun register(key: String, callbackName: String, fn: HookFn) {
        val existing = table.getOrPut(key) { HashMap() }
        check(callbackName !in existing) { "Duplicate engine hook $key.$callbackName" }
        existing[callbackName] = fn
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for (set in EngineHookSets.all) set.install()
            loaded = true
        }
    }
}

/** A group of ported handlers, written as `effect("condition:brn") { on("Residual") { ... } }`. */
abstract class HookSet {
    abstract fun HookRegistrar.define()

    fun install() = HookRegistrar().define()
}

class HookRegistrar {
    fun effect(key: String, body: EffectHooks.() -> Unit) = EffectHooks(key).body()

    fun condition(id: String, body: EffectHooks.() -> Unit) = effect("condition:$id", body)
    fun move(id: String, body: EffectHooks.() -> Unit) = effect("move:$id", body)
    fun ability(id: String, body: EffectHooks.() -> Unit) = effect("ability:$id", body)
    fun item(id: String, body: EffectHooks.() -> Unit) = effect("item:$id", body)
}

class EffectHooks(private val key: String) {
    /** `onX` handler; the priority and order come from the exported data, as in Showdown. */
    fun on(event: String, fn: HookFn) = EngineHooks.register(key, "on$event", fn)

    /** A named callback such as `basePowerCallback` or `durationCallback`. */
    fun callback(name: String, fn: HookFn) = EngineHooks.register(key, name, fn)

    /** Handlers of the effect's `condition` block (a move's volatile, an item's or ability's condition). */
    fun condition(body: EffectHooks.() -> Unit) = EffectHooks("$key/condition").body()

    /** Handlers of the move's single `secondary` block. */
    fun secondary(body: EffectHooks.() -> Unit) {
        EffectHooks("$key/secondary").body()
        EffectHooks("$key/secondaries.0").body()
    }

    fun secondaries(index: Int, body: EffectHooks.() -> Unit) = EffectHooks("$key/secondaries.$index").body()

    fun self(body: EffectHooks.() -> Unit) = EffectHooks("$key/self").body()
}
