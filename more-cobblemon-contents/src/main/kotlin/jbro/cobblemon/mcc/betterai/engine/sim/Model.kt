package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.HitEffect
import jbro.cobblemon.mcc.betterai.engine.dex.MoveData

/**
 * An effect's data store (`effectState`): a JS plain object. The fields the core reads are typed; anything
 * a handler invents (`stage`, `layers`, `counter` ...) lives in the map, in insertion order.
 */
class EffectState(var id: String) {
    var duration: Int? = null
    var target: Any? = null
    var source: Pokemon? = null
    var sourceSlot: String? = null
    var sourceEffect: EffectLike? = null
    val values: LinkedHashMap<String, Any?> = LinkedHashMap()

    operator fun get(key: String): Any? = values[key]

    operator fun set(key: String, value: Any?) {
        if (value === Unit) values.remove(key) else values[key] = value
    }

    fun has(key: String): Boolean = values.containsKey(key)

    fun remove(key: String) {
        values.remove(key)
    }

    fun int(key: String): Int = Js.int(values[key])

    /** `state[getKey]` for residual handler discovery (`duration` is the only key Showdown asks for). */
    fun truthy(key: String): Boolean = if (key == "duration") Js.truthy(duration) else Js.truthy(values[key])
}

class MoveSlot(
    var move: String,
    var id: String,
    var pp: Int,
    var maxpp: Int,
    var target: String,
    /** `false`, `true` or `"hidden"`. */
    var disabled: Any = false,
    var disabledSource: String = "",
    var used: Boolean = false,
    var virtual: Boolean = false,
) {
    fun copy(): MoveSlot = MoveSlot(move, id, pp, maxpp, target, disabled, disabledSource, used, virtual)
}

/** A team member as Showdown receives it (a Cobblemon-flavoured `PokemonSet`). */
class PokemonSet(
    var species: String,
    var name: String = "",
    var level: Int = 100,
    var gender: String = "",
    var ability: String = "",
    var item: String = "",
    var nature: String = "",
    val evs: MutableMap<String, Int> = linkedMapOf(),
    val ivs: MutableMap<String, Int> = linkedMapOf(),
    val moves: List<String>,
    /** Per-move `pp`/`maxPp`, as Cobblemon sends them. Null: Showdown's default PP with PP Ups. */
    val movesInfo: List<IntArray>? = null,
    var teraType: String? = null,
    var uuid: String = "",
    var shiny: Boolean = false,
    var happiness: Int? = null,
    var dynamaxLevel: Int? = null,
    var gigantamax: Boolean = false,
    var hpType: String? = null,
    var currentHealth: Int? = null,
    var status: String? = null,
    var statusDuration: Int? = null,
    var pokeball: String? = null,
)

class MoveHitData {
    var crit: Boolean = false
    var typeMod: Int = 0
    var zBrokeProtect: Boolean = false
}

/**
 * What a move needs while it is being used: either the move itself, or a secondary/self block handed to
 * `spreadMoveHit` as `moveData`.
 */
interface HitData : EffectLike {
    val hitFlags: Map<String, Any?>
    val hitBoosts: Map<String, Int>?
    val hitHeal: IntArray?
    val hitStatus: String?
    val hitForceStatus: String?
    val hitVolatileStatus: String?
    val hitSideCondition: String?
    val hitSlotCondition: String?
    val hitWeather: String?
    val hitTerrain: String?
    val hitPseudoWeather: String?
    val hitForceSwitch: Boolean
    val hitSelfdestruct: String?
    val hitSelfSwitch: Any?
    val hitSelf: HitData?
    val hitSecondaries: List<HitData>?
    val chance: Int?
    val hitAbility: EffectLike?
}

/** A secondary or self block wrapped for `spreadMoveHit`. */
class SecondaryHit(val effect: HitEffect) : HitData, EffectLike by effect {
    override val hitFlags: Map<String, Any?> get() = effect.flags
    override val hitBoosts: Map<String, Int>? get() = effect.boosts
    override val hitHeal: IntArray? get() = effect.list("heal")?.map { Js.int(it) }?.toIntArray()
    override val hitStatus: String? get() = effect.status
    override val hitForceStatus: String? get() = effect.string("forceStatus")
    override val hitVolatileStatus: String? get() = effect.volatileStatus
    override val hitSideCondition: String? get() = effect.sideCondition
    override val hitSlotCondition: String? get() = effect.slotCondition
    override val hitWeather: String? get() = effect.weather
    override val hitTerrain: String? get() = effect.terrain
    override val hitPseudoWeather: String? get() = effect.pseudoWeather
    override val hitForceSwitch: Boolean get() = effect.bool("forceSwitch")
    override val hitSelfdestruct: String? get() = effect.string("selfdestruct")
    override val hitSelfSwitch: Any? get() = effect.data("selfSwitch")
    override val hitSelf: HitData? get() = effect.self?.let { SecondaryHit(it) }
    override val hitSecondaries: List<HitData>? get() = null
    override val chance: Int? get() = effect.chance
    override val hitAbility: EffectLike? get() = null
}

/**
 * A move in use: Showdown's `ActiveMove`, a mutable copy of the move's data. Handlers change it freely
 * (`basePower`, `type`, `secondaries` ...), so every use starts from a fresh copy.
 */
class ActiveMove(val template: MoveData) : HitData {
    override var id: String = template.id
    override var name: String = template.name
    override val fullname: String get() = "move: $name"
    override val effectType: String get() = "Move"
    override val num: Int get() = template.num
    override val hookKey: String get() = template.hookKey

    var type: String = template.type
    var baseMoveType: String = template.string("baseMoveType") ?: template.type
    var category: String = template.category
    var basePower: Int = template.basePower
    /** `true` or a number. */
    var accuracy: Any = template.accuracy
    var pp: Int = template.pp
    var priority: Int = template.priority
    var target: String = template.target
    val flags: MutableMap<String, Any?> = LinkedHashMap(template.flags)
    var critRatio: Int = template.critRatio
    var willCrit: Boolean? = template.data("willCrit") as? Boolean
    var secondaries: MutableList<HitData>? = template.secondaryEffects?.map<_, HitData> { SecondaryHit(it) }?.toMutableList()
    var self: HitData? = template.selfEffect?.let { SecondaryHit(it) }
    var selfBoost: Map<String, Int>? = template.map("selfBoost")?.let { sb ->
        @Suppress("UNCHECKED_CAST") (sb["boosts"] as? Map<String, Any?>)?.mapValues { Js.int(it.value) }
    }
    var boosts: Map<String, Int>? = template.map("boosts")?.mapValues { Js.int(it.value) }
    var status: String? = template.string("status")
    var forceStatus: String? = template.string("forceStatus")
    var volatileStatus: String? = template.string("volatileStatus")
    var sideCondition: String? = template.string("sideCondition")
    var slotCondition: String? = template.string("slotCondition")
    var weather: String? = template.string("weather")
    var terrain: String? = template.string("terrain")
    var pseudoWeather: String? = template.string("pseudoWeather")
    var heal: IntArray? = template.list("heal")?.map { Js.int(it) }?.toIntArray()
    var drain: IntArray? = template.list("drain")?.map { Js.int(it) }?.toIntArray()
    var recoil: IntArray? = template.list("recoil")?.map { Js.int(it) }?.toIntArray()
    /** A number or `[min, max]`. */
    var multihit: Any? = template.data("multihit")
    var multihitType: String? = template.string("multihitType")
    var multiaccuracy: Boolean = template.bool("multiaccuracy")
    /** `true`, a type name, or null. */
    var ohko: Any? = template.data("ohko")
    var selfdestruct: String? = template.string("selfdestruct")
    var selfSwitch: Any? = template.data("selfSwitch")
    var forceSwitch: Boolean = template.bool("forceSwitch")
    var breaksProtect: Boolean = template.bool("breaksProtect")
    var stealsBoosts: Boolean = template.bool("stealsBoosts")
    /** `true`, `false`, or a map of type names; `null` for "unset" before the first hit step. */
    var ignoreImmunity: Any? = template.data("ignoreImmunity")
    var ignoreAbility: Boolean = template.bool("ignoreAbility")
    var ignoreAccuracy: Boolean = template.bool("ignoreAccuracy")
    var ignoreEvasion: Boolean = template.bool("ignoreEvasion")
    var ignoreDefensive: Boolean = template.bool("ignoreDefensive")
    var ignoreOffensive: Boolean = template.bool("ignoreOffensive")
    var ignoreNegativeOffensive: Boolean = template.bool("ignoreNegativeOffensive")
    var ignorePositiveDefensive: Boolean = template.bool("ignorePositiveDefensive")
    var overrideOffensiveStat: String? = template.string("overrideOffensiveStat")
    var overrideOffensivePokemon: String? = template.string("overrideOffensivePokemon")
    var overrideDefensiveStat: String? = template.string("overrideDefensiveStat")
    var overrideDefensivePokemon: String? = template.string("overrideDefensivePokemon")
    /** `"level"`, a number, or null. */
    var damage: Any? = template.data("damage")
    var isZ: Any? = template.isZ
    var isMax: Any? = template.isMax
    var hasSheerForce: Boolean = template.bool("hasSheerForce")
    var spreadHit: Boolean = template.bool("spreadHit")
    var spreadModifier: Double? = template.number("spreadModifier")
    var critModifier: Double? = template.number("critModifier")
    var forceSTAB: Boolean = template.bool("forceSTAB")
    /** `true`, `false`, or unset (`null`); Dragon Darts flips it during a use. */
    var smartTarget: Boolean? = template.data("smartTarget") as? Boolean
    var tracksTarget: Boolean = template.bool("tracksTarget")
    var sleepUsable: Boolean = template.sleepUsable
    var thawsTarget: Boolean = template.bool("thawsTarget")
    var hasCrashDamage: Boolean = template.bool("hasCrashDamage")
    var stallingMove: Boolean = template.bool("stallingMove")
    var noPPBoosts: Boolean = template.noPPBoosts
    var struggleRecoil: Boolean = template.bool("struggleRecoil")
    var mindBlownRecoil: Boolean = template.bool("mindBlownRecoil")
    var realMove: String? = template.string("realMove")
    var nonGhostTarget: String = template.nonGhostTarget
    var pressureTarget: String = template.string("pressureTarget") ?: ""
    var alwaysHit: Boolean = template.bool("alwaysHit")
    var negateSecondary: Boolean = template.bool("negateSecondary")
    var infiltrates: Boolean = template.bool("infiltrates")
    var zMoveData: Map<String, Any?>? = template.map("zMove")
    var maxMoveData: Map<String, Any?>? = template.map("maxMove")

    // Per-use state Showdown attaches to the ActiveMove.
    var hit: Int = 0
    var totalDamage: Int = 0
    var sourceEffect: String = ""
    var isExternal: Boolean = false
    var pranksterBoosted: Boolean = false
    var hasBounced: Boolean = false
    var selfDropped: Boolean = false
    var isZOrMaxPowered: Boolean = false
    var baseMove: String? = null
    var stab: Double? = null
    var typeChangerBoosted: EffectLike? = null
    var hasAuraBreak: Boolean? = null
    var auraBooster: Pokemon? = null
    var statusRoll: String? = null
    var lastHit: Boolean = false
    var moveHitData: HashMap<String, MoveHitData>? = null
    /** Anything else a handler sets on the move. */
    val extra: HashMap<String, Any?> = HashMap()
    /** Handlers a handler attaches to the move while it is in use, like Fling's `move.onHit = ...`. */
    val dynamicHandlers: HashMap<String, Any?> = HashMap()

    /**
     * A handler set on this move while it is in use wins over the template's: Fling's `move.onHit`
     * (dynamicHandlers) or Piercing Drill's `move.onBasePower` (a function stored in extra).
     */
    override fun handler(callbackName: String): Any? = when {
        dynamicHandlers.containsKey(callbackName) -> dynamicHandlers[callbackName]
        extra[callbackName] is Function1<*, *> -> extra[callbackName]
        else -> template.handler(callbackName)
    }
    override fun declares(callbackName: String): Boolean =
        callbackName in dynamicHandlers || extra[callbackName] is Function1<*, *> || template.declares(callbackName)
    override fun data(field: String): Any? = if (extra.containsKey(field)) extra[field] else template.data(field)
    override fun flag(name: String): Boolean = Js.truthy(flags[name])

    override val hitFlags: Map<String, Any?> get() = flags
    override val hitBoosts: Map<String, Int>? get() = boosts
    override val hitHeal: IntArray? get() = heal
    override val hitStatus: String? get() = status
    override val hitForceStatus: String? get() = forceStatus
    override val hitVolatileStatus: String? get() = volatileStatus
    override val hitSideCondition: String? get() = sideCondition
    override val hitSlotCondition: String? get() = slotCondition
    override val hitWeather: String? get() = weather
    override val hitTerrain: String? get() = terrain
    override val hitPseudoWeather: String? get() = pseudoWeather
    override val hitForceSwitch: Boolean get() = forceSwitch
    override val hitSelfdestruct: String? get() = selfdestruct
    override val hitSelfSwitch: Any? get() = selfSwitch
    override val hitSelf: HitData? get() = self
    override val hitSecondaries: List<HitData>? get() = secondaries
    override val chance: Int? get() = null
    override val hitAbility: EffectLike? get() = null

    val isStatus: Boolean get() = category == "Status"

    override fun toString(): String = name

    /** `dex.getActiveMove(move)` on an ActiveMove returns it as is; a clone copies the per-use state too. */
    fun copy(): ActiveMove {
        val c = ActiveMove(template)
        c.id = id; c.name = name; c.type = type; c.baseMoveType = baseMoveType; c.category = category
        c.basePower = basePower; c.accuracy = accuracy; c.pp = pp; c.priority = priority; c.target = target
        c.flags.clear(); c.flags.putAll(flags); c.critRatio = critRatio; c.willCrit = willCrit
        c.secondaries = secondaries?.toMutableList(); c.self = self; c.selfBoost = selfBoost; c.boosts = boosts
        c.status = status; c.forceStatus = forceStatus; c.volatileStatus = volatileStatus
        c.sideCondition = sideCondition; c.slotCondition = slotCondition; c.weather = weather; c.terrain = terrain
        c.pseudoWeather = pseudoWeather; c.heal = heal; c.drain = drain; c.recoil = recoil; c.multihit = multihit
        c.multihitType = multihitType; c.multiaccuracy = multiaccuracy; c.ohko = ohko; c.selfdestruct = selfdestruct
        c.selfSwitch = selfSwitch; c.forceSwitch = forceSwitch; c.breaksProtect = breaksProtect
        c.stealsBoosts = stealsBoosts; c.ignoreImmunity = ignoreImmunity; c.ignoreAbility = ignoreAbility
        c.ignoreAccuracy = ignoreAccuracy; c.ignoreEvasion = ignoreEvasion; c.ignoreDefensive = ignoreDefensive
        c.ignoreOffensive = ignoreOffensive; c.ignoreNegativeOffensive = ignoreNegativeOffensive
        c.ignorePositiveDefensive = ignorePositiveDefensive; c.overrideOffensiveStat = overrideOffensiveStat
        c.overrideOffensivePokemon = overrideOffensivePokemon; c.overrideDefensiveStat = overrideDefensiveStat
        c.overrideDefensivePokemon = overrideDefensivePokemon; c.damage = damage; c.isZ = isZ; c.isMax = isMax
        c.hasSheerForce = hasSheerForce; c.spreadHit = spreadHit; c.spreadModifier = spreadModifier
        c.critModifier = critModifier; c.forceSTAB = forceSTAB; c.smartTarget = smartTarget
        c.tracksTarget = tracksTarget; c.sleepUsable = sleepUsable; c.thawsTarget = thawsTarget
        c.hasCrashDamage = hasCrashDamage; c.stallingMove = stallingMove; c.noPPBoosts = noPPBoosts
        c.struggleRecoil = struggleRecoil; c.mindBlownRecoil = mindBlownRecoil; c.realMove = realMove
        c.nonGhostTarget = nonGhostTarget; c.pressureTarget = pressureTarget; c.alwaysHit = alwaysHit
        c.negateSecondary = negateSecondary; c.infiltrates = infiltrates; c.zMoveData = zMoveData
        c.maxMoveData = maxMoveData; c.hit = hit; c.totalDamage = totalDamage; c.sourceEffect = sourceEffect
        c.isExternal = isExternal; c.pranksterBoosted = pranksterBoosted; c.hasBounced = hasBounced
        c.selfDropped = selfDropped; c.isZOrMaxPowered = isZOrMaxPowered; c.baseMove = baseMove; c.stab = stab
        c.typeChangerBoosted = typeChangerBoosted; c.hasAuraBreak = hasAuraBreak; c.auraBooster = auraBooster
        c.statusRoll = statusRoll; c.lastHit = lastHit
        c.moveHitData = moveHitData?.let { HashMap(it) }
        c.extra.putAll(extra)
        c.dynamicHandlers.putAll(dynamicHandlers)
        return c
    }
}

/**
 * A hit block a handler builds on the fly, like Tera Blast's `move.self = { boosts: { atk: -1, spa: -1 } }`.
 * It has no handlers of its own.
 */
class LiteralHit(
    override val hitBoosts: Map<String, Int>? = null,
    override val chance: Int? = null,
    override val hitStatus: String? = null,
    override val hitVolatileStatus: String? = null,
    override val hitSelf: HitData? = null,
) : HitData {
    override val id: String get() = ""
    override val name: String get() = ""
    override val fullname: String get() = ""
    override val effectType: String get() = ""
    override val num: Int get() = 0
    override val hookKey: String get() = ""
    override fun handler(callbackName: String): Any? = null
    override fun declares(callbackName: String): Boolean = false
    override fun data(field: String): Any? = null
    override val hitFlags: Map<String, Any?> get() = emptyMap()
    override val hitHeal: IntArray? get() = null
    override val hitForceStatus: String? get() = null
    override val hitSideCondition: String? get() = null
    override val hitSlotCondition: String? get() = null
    override val hitWeather: String? get() = null
    override val hitTerrain: String? get() = null
    override val hitPseudoWeather: String? get() = null
    override val hitForceSwitch: Boolean get() = false
    override val hitSelfdestruct: String? get() = null
    override val hitSelfSwitch: Any? get() = null
    override val hitSecondaries: List<HitData>? get() = null
    override val hitAbility: EffectLike? get() = null
}
