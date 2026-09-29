package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.dex.MissingHook
import jbro.cobblemon.mcc.betterai.engine.dex.Species
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookFn
import kotlin.math.floor

/** A log part that prints differently for its side's player and for everyone else (`getHealth`, `getDetails`). */
class Split(val side: String, val secret: String, val shared: String)

class SplitPart(private val producer: () -> Split) {
    fun produce(): Split = producer()
}

/** Options for a battle, as `new Battle({format, seed})` would take them for a Cobblemon format. */
class BattleOptions(
    val gameType: String = "singles",
    val formatName: String = "Cobblemon Singles",
    val seed: IntArray = intArrayOf(1, 2, 3, 4),
    /** Keep Showdown's protocol log. Off for search, where nobody reads it. */
    val log: Boolean = true,
    val strictChoices: Boolean = true,
    /** Wait for an explicit [Battle.start] after both players join, like Showdown's `deserialized` option. */
    val deferStart: Boolean = false,
)

/**
 * Sees what the protocol log would say even when the log is off: move messages, damage rolls and HP
 * lines. The native search's branch worker uses it for move order, damage evidence and recoil.
 */
interface BattleTracer {
    fun move(battle: Battle, pokemon: Pokemon, moveName: String)

    /** Returns the damage to use; [actual] is what the battle's own roll gave. */
    fun randomizer(battle: Battle, baseDamage: Int, actual: Int): Int

    fun hpLine(battle: Battle, kind: String, pokemon: Pokemon, extras: List<String>)
}

/**
 * Port of `sim/battle.js`: the event system, the turn loop, damage, healing and stat stages. Gen 9 only,
 * with the Cobblemon and Mega Showdown changes the dev server runs.
 */
class Battle(val dex: EngineDex, val options: BattleOptions) {
    val logEnabled = options.log
    val log: MutableList<String> = ArrayList()
    val gameType: String = options.gameType
    val activePerHalf: Int = if (gameType == "triples") 3 else if (gameType == "doubles") 2 else 1
    var prng: Prng = Prng(options.seed)
    val sides: MutableList<Side> = ArrayList()
    val field: Field = Field(this)
    val queue: BattleQueue = BattleQueue(this)
    val actions: BattleActions = BattleActions(this)
    val faintQueue: MutableList<FaintData> = ArrayList()
    val strictChoices = options.strictChoices
    var requestState: String = ""
    var turn = 0
    var midTurn = false
    var started = false
    var ended = false
    var winner: String? = null
    /** Showdown starts with `effect = {id: ''}` and `event = {id: ''}`: truthy objects outside any event. */
    var effect: EffectLike = EMPTY_EFFECT
    var effectState: EffectState = EffectState("")
    var event: BattleEvent? = TOP_EVENT
    var eventDepth = 0
    var activeMove: ActiveMove? = null
    var activePokemon: Pokemon? = null
    var activeTarget: Pokemon? = null
    var lastMove: ActiveMove? = null
    var lastMoveLine = -1
    var lastSuccessfulMoveThisTurn: String? = null
    var lastDamage = 0
    var abilityOrder = 0
    val hints: MutableSet<String> = HashSet()
    var tracer: BattleTracer? = null
    /** Handlers Showdown has in code that the engine reached without an implementation. */
    val missingHooks: MutableSet<String> = LinkedHashSet()

    class FaintData(val target: Pokemon, val source: Pokemon?, val effect: EffectLike?)

    class BattleEvent(val id: String, var target: Any?, val source: Any?, val effect: Any?, var modifier: Double = 1.0)

    init {
        add("t:", 0)
        add("gametype", gameType)
    }

    val p1: Side get() = sides[0]
    val p2: Side get() = sides[1]

    // region Randomness

    fun random(): Double = prng.next()
    fun randomReal(): Double = prng.next()
    fun random(n: Int): Int = prng.next(n)
    fun random(m: Int, n: Int): Int = prng.next(m, n)
    fun randomChance(numerator: Int, denominator: Int): Boolean = prng.randomChance(numerator, denominator)

    /** Accuracy can be fractional after multi-accuracy boosts; JS compares the roll against it as is. */
    fun randomChance(numerator: Double, denominator: Int): Boolean = prng.next(denominator) < numerator
    fun <T> sample(items: List<T>): T = prng.sample(items)

    // endregion

    fun suppressingAbility(target: Pokemon?): Boolean {
        val active = activePokemon ?: return false
        val move = activeMove ?: return false
        return active.isActive && active !== target && move.ignoreAbility && target?.hasItem("abilityshield") != true
    }

    fun setActiveMove(move: ActiveMove?, pokemon: Pokemon? = null, target: Pokemon? = null) {
        activeMove = move
        activePokemon = pokemon
        activeTarget = target ?: pokemon
    }

    fun clearActiveMove(failed: Boolean = false) {
        if (activeMove != null) {
            if (!failed) lastMove = activeMove
            activeMove = null
            activePokemon = null
            activeTarget = null
        }
    }

    fun updateSpeed() {
        for (pokemon in getAllActive()) pokemon.updateSpeed()
    }

    /** Sorts a list the way the games resolve ties: equal entries are shuffled with the battle's PRNG. */
    fun <T : Prioritized> speedSort(list: MutableList<T>, comparator: (T, T) -> Int = { a, b -> comparePriority(a, b) }) {
        if (list.size < 2) return
        var sorted = 0
        while (sorted + 1 < list.size) {
            var nextIndexes = mutableListOf(sorted)
            for (i in sorted + 1 until list.size) {
                val delta = comparator(list[nextIndexes[0]], list[i])
                if (delta < 0) continue
                if (delta > 0) nextIndexes = mutableListOf(i)
                if (delta == 0) nextIndexes.add(i)
            }
            for (i in nextIndexes.indices) {
                val index = nextIndexes[i]
                if (index != sorted + i) {
                    val swap = list[sorted + i]
                    list[sorted + i] = list[index]
                    list[index] = swap
                }
            }
            if (nextIndexes.size > 1) prng.shuffle(list, sorted, sorted + nextIndexes.size)
            sorted += nextIndexes.size
        }
    }

    private class SpeedEntry(val pokemon: Pokemon) : Prioritized {
        override val sortOrder: Int? get() = null
        override val sortPriority: Double get() = 0.0
        override val sortSpeed: Double get() = pokemon.speed.toDouble()
        override val sortSubOrder: Int get() = 0
    }

    fun eachEvent(eventid: String, effectIn: EffectLike? = null, relayVar: Any? = Unit) {
        val actives = getAllActive().map { SpeedEntry(it) }.toMutableList()
        val effect = effectIn ?: this.effect
        speedSort(actives) { a, b -> b.pokemon.speed - a.pokemon.speed }
        for (entry in actives) runEvent(eventid, entry.pokemon, null, effect, relayVar)
        if (eventid == "Weather") eachEvent("Update")
    }

    fun residualEvent(eventid: String, relayVar: Any? = Unit) {
        val callbackName = "on$eventid"
        val handlers = ArrayList<EventHandler>()
        handlers.addAll(findFieldEventHandlers(field, "onField$eventid", "duration"))
        for (side in sides) {
            if (side.n < 2 || side.allySide == null) handlers.addAll(findSideEventHandlers(side, "onSide$eventid", "duration"))
            for (active in side.active) {
                if (active == null) continue
                handlers.addAll(findPokemonEventHandlers(active, callbackName, "duration"))
                handlers.addAll(findSideEventHandlers(side, callbackName, null, active))
                handlers.addAll(findFieldEventHandlers(field, callbackName, null, active))
            }
        }
        speedSort(handlers)
        while (handlers.isNotEmpty()) {
            val handler = handlers.removeAt(0)
            val effect = handler.effect
            val holder = handler.effectHolder
            if (holder is Pokemon && holder.fainted) continue
            val state = handler.state
            if (handler.end != null && state != null && Js.truthy(state.duration)) {
                state.duration = state.duration!! - 1
                if (state.duration == 0) {
                    handler.end.invoke()
                    if (ended) return
                    continue
                }
            }
            var handlerEventid = eventid
            if (holder is Side) handlerEventid = "Side$eventid"
            if (holder is Field) handlerEventid = "Field$eventid"
            if (handler.callback != null) {
                singleEvent(handlerEventid, effect, state, holder, null, null, relayVar, handler.callback)
            }
            faintMessages()
            if (ended) return
        }
    }

    /** The entire event system revolves around this function and [runEvent]. */
    fun singleEvent(
        eventid: String,
        effect: EffectLike,
        state: EffectState?,
        target: Any?,
        source: Any? = null,
        sourceEffect: Any? = null,
        relayVarIn: Any? = Unit,
        customCallback: Any? = null,
    ): Any? {
        if (eventDepth >= 8) throw IllegalStateException("Stack overflow in $eventid")
        var relayVar = relayVarIn
        var hasRelayVar = true
        if (relayVar === Unit) {
            relayVar = true
            hasRelayVar = false
        }
        if (effect.effectType == "Status" && target is Pokemon && target.status != effect.id) return relayVar
        if (eventid != "Start" && eventid != "TakeItem" && eventid != "Primal" && effect.effectType == "Item" &&
            target is Pokemon && target.ignoringItem()) return relayVar
        if (eventid != "End" && effect.effectType == "Ability" && target is Pokemon && target.ignoringAbility()) return relayVar
        if (effect.effectType == "Weather" && eventid != "FieldStart" && eventid != "FieldResidual" && eventid != "FieldEnd" &&
            field.suppressingWeather()) return relayVar
        val callback = customCallback ?: effect.handler("on$eventid") ?: return relayVar
        val parentEffect = this.effect
        val parentEffectState = this.effectState
        val parentEvent = this.event
        this.effect = effect
        this.effectState = state ?: EffectState("")
        this.event = BattleEvent(eventid, target, source, sourceEffect)
        eventDepth++
        val returnVal = try {
            invoke(callback, relayVar.takeIf { hasRelayVar } ?: Unit, target, source, sourceEffect, this.effectState, effect)
        } finally {
            eventDepth--
            this.effect = parentEffect
            this.effectState = parentEffectState
            this.event = parentEvent
        }
        return if (returnVal === Unit) relayVar else returnVal
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(callback: Any, relay: Any?, target: Any?, source: Any?, sourceEffect: Any?, state: EffectState, effect: EffectLike): Any? =
        when (callback) {
            is MissingHook -> {
                missingHooks.add(callback.toString())
                Unit
            }
            is Function1<*, *> -> (callback as HookFn).invoke(HookCall(this, relay, target, source, sourceEffect, state, effect))
            else -> callback
        }

    /** Calls an effect's named callback (`basePowerCallback`, `durationCallback` ...) as `fn.call(battle, a, b, c)`. */
    fun callback(effect: EffectLike, name: String, a: Any?, b: Any?, c: Any?): Any? {
        val fn = effect.handler(name) ?: return Unit
        return invoke(fn, Unit, a, b, c, effectState, effect)
    }

    fun runEvent(
        eventid: String,
        targetIn: Any? = null,
        source: Any? = null,
        sourceEffect: Any? = null,
        relayVarIn: Any? = Unit,
        onEffect: Boolean = false,
        fastExit: Boolean = false,
    ): Any? {
        if (eventDepth >= 8) throw IllegalStateException("Stack overflow in $eventid")
        val target: Any = targetIn ?: this
        val effectSource = source as? Pokemon
        val handlers = findEventHandlers(target, eventid, effectSource)
        if (onEffect) {
            val se = sourceEffect as? EffectLike ?: error("onEffect passed without an effect")
            val callback = se.handler("on$eventid")
            if (callback != null) {
                handlers.add(0, resolvePriority(EventHandler(se, callback, EffectState(""), null, target), "on$eventid"))
            }
        }
        when {
            eventid in LEFT_TO_RIGHT_EVENTS -> handlers.sortWith { a, b -> compareLeftToRightOrder(a, b) }
            fastExit -> handlers.sortWith { a, b -> compareRedirectOrder(a, b) }
            else -> speedSort(handlers)
        }
        var relayVar = relayVarIn
        var hasRelayVar = true
        if (relayVar === Unit || relayVar == null) {
            relayVar = true
            hasRelayVar = false
        }
        var argTarget: Any? = target
        var argRelay: Any? = relayVar
        val parentEvent = this.event
        this.event = BattleEvent(eventid, target, source, sourceEffect, 1.0)
        eventDepth++
        val isArray = target is List<*>
        val targetRelayVars: MutableList<Any?> = if (isArray) {
            val list = target as List<*>
            @Suppress("UNCHECKED_CAST")
            if (relayVar is List<*>) (relayVar as List<Any?>).toMutableList() else MutableList(list.size) { true }
        } else ArrayList()
        try {
            for (handler in handlers) {
                val index = handler.index
                if (index != null) {
                    val current = targetRelayVars[index]
                    if (!Js.truthy(current) && !(current == 0 && eventid == "DamagingHit")) continue
                    if (handler.target != null) {
                        argTarget = handler.target
                        this.event!!.target = handler.target
                    }
                    if (hasRelayVar) argRelay = targetRelayVars[index]
                }
                val effect = handler.effect
                val holder = handler.effectHolder
                if (effect.effectType == "Status" && (holder as Pokemon).status != effect.id) continue
                if (effect.effectType == "Ability" && effect.flag("breakable") && suppressingAbility(holder as? Pokemon)) continue
                if (eventid != "Start" && eventid != "SwitchIn" && eventid != "TakeItem" && effect.effectType == "Item" &&
                    holder is Pokemon && holder.ignoringItem()) continue
                else if (eventid != "End" && effect.effectType == "Ability" && holder is Pokemon && holder.ignoringAbility()) continue
                if ((effect.effectType == "Weather" || eventid == "Weather") && eventid != "Residual" && eventid != "End" &&
                    field.suppressingWeather()) continue
                val callback = handler.callback ?: continue
                val returnVal: Any? = if (callback is Function1<*, *> || callback is MissingHook) {
                    val parentEffect = this.effect
                    val parentEffectState = this.effectState
                    this.effect = handler.effect
                    val state = handler.state ?: EffectState("")
                    this.effectState = state
                    state.target = holder
                    try {
                        invoke(callback, if (hasRelayVar) argRelay else Unit, argTarget, source, sourceEffect, state, handler.effect)
                    } finally {
                        this.effect = parentEffect
                        this.effectState = parentEffectState
                    }
                } else callback
                if (returnVal !== Unit) {
                    relayVar = returnVal
                    if (!Js.truthy(relayVar) || fastExit) {
                        if (index != null) {
                            targetRelayVars[index] = relayVar
                            if (targetRelayVars.all { !Js.truthy(it) }) break
                        } else {
                            break
                        }
                    }
                    if (hasRelayVar) argRelay = relayVar
                }
            }
        } finally {
            eventDepth--
        }
        if (Js.isNumber(relayVar)) {
            val v = Js.num(relayVar)
            if (v == Math.abs(floor(v))) relayVar = modify(Js.int(relayVar), this.event!!.modifier)
        }
        this.event = parentEvent
        return if (isArray) targetRelayVars else relayVar
    }

    fun priorityEvent(eventid: String, target: Any?, source: Any? = null, effect: Any? = null, relayVar: Any? = Unit, onEffect: Boolean = false): Any? =
        runEvent(eventid, target, source, effect, relayVar, onEffect, true)

    class EventHandler(
        val effect: EffectLike,
        val callback: Any?,
        val state: EffectState?,
        val end: (() -> Any?)?,
        val effectHolder: Any,
    ) : Prioritized {
        var order: Int? = null
        var priority: Double = 0.0
        var subOrder: Int = 0
        var speed: Double = 0.0
        var index: Int? = null
        var target: Pokemon? = null
        override val sortOrder: Int? get() = order
        override val sortPriority: Double get() = priority
        override val sortSpeed: Double get() = speed
        override val sortSubOrder: Int get() = subOrder
    }

    fun resolvePriority(handler: EventHandler, callbackName: String): EventHandler {
        handler.order = handler.effect.number("${callbackName}Order")?.toInt()?.takeIf { it != 0 }
        handler.priority = handler.effect.number("${callbackName}Priority") ?: 0.0
        handler.subOrder = handler.effect.number("${callbackName}SubOrder")?.toInt() ?: 0
        val holder = handler.effectHolder
        if (holder is Pokemon) handler.speed = holder.speed.toDouble()
        return handler
    }

    fun findEventHandlers(targetIn: Any, eventName: String, source: Pokemon?): MutableList<EventHandler> {
        var handlers = ArrayList<EventHandler>()
        if (targetIn is List<*>) {
            for ((i, pokemon) in targetIn.withIndex()) {
                if (pokemon == null || pokemon == false) continue
                val current = findEventHandlers(pokemon, eventName, source)
                for (h in current) {
                    h.target = pokemon as Pokemon
                    h.index = i
                }
                handlers.addAll(current)
            }
            return handlers
        }
        var target: Any = targetIn
        val prefixed = eventName !in UNPREFIXED_EVENTS
        if (target is Pokemon && (target.isActive || source?.isActive == true)) {
            handlers = findPokemonEventHandlers(target, "on$eventName")
            if (prefixed) {
                for (ally in target.alliesAndSelf()) {
                    handlers.addAll(findPokemonEventHandlers(ally, "onAlly$eventName"))
                    handlers.addAll(findPokemonEventHandlers(ally, "onAny$eventName"))
                }
                for (foe in target.foes()) {
                    handlers.addAll(findPokemonEventHandlers(foe, "onFoe$eventName"))
                    handlers.addAll(findPokemonEventHandlers(foe, "onAny$eventName"))
                }
            }
            target = target.side
        }
        if (source != null && prefixed) handlers.addAll(findPokemonEventHandlers(source, "onSource$eventName"))
        if (target is Side) {
            for (side in sides) {
                if (side.n >= 2 && side.allySide != null) break
                if (side === target || side === target.allySide) {
                    handlers.addAll(findSideEventHandlers(side, "on$eventName"))
                } else if (prefixed) {
                    handlers.addAll(findSideEventHandlers(side, "onFoe$eventName"))
                }
                if (prefixed) handlers.addAll(findSideEventHandlers(side, "onAny$eventName"))
            }
        }
        handlers.addAll(findFieldEventHandlers(field, "on$eventName"))
        return handlers
    }

    fun findPokemonEventHandlers(pokemon: Pokemon, callbackName: String, getKey: String? = null): ArrayList<EventHandler> {
        val handlers = ArrayList<EventHandler>()
        val status = pokemon.getStatus()
        var callback = status.handler(callbackName)
        if (callback != null || (getKey != null && pokemon.statusState.truthy(getKey))) {
            handlers.add(resolvePriority(EventHandler(status, callback, pokemon.statusState, { pokemon.clearStatus() }, pokemon), callbackName))
        }
        for ((id, volatileState) in pokemon.volatiles.entries.toList()) {
            val volatile = dex.conditionById(id)
            callback = volatile.handler(callbackName)
            if (callback != null || (getKey != null && volatileState.truthy(getKey))) {
                handlers.add(resolvePriority(EventHandler(volatile, callback, volatileState, { pokemon.removeVolatile(volatile.id) }, pokemon), callbackName))
            }
        }
        val ability = pokemon.getAbility()
        callback = ability.handler(callbackName)
        if (callback != null || (getKey != null && pokemon.abilityState.truthy(getKey))) {
            handlers.add(resolvePriority(EventHandler(ability, callback, pokemon.abilityState, { pokemon.clearAbility() }, pokemon), callbackName))
        }
        val item = pokemon.getItem()
        callback = item.handler(callbackName)
        if (callback != null || (getKey != null && pokemon.itemState.truthy(getKey))) {
            handlers.add(resolvePriority(EventHandler(item, callback, pokemon.itemState, { pokemon.clearItem() }, pokemon), callbackName))
        }
        speciesEffect(pokemon.baseSpecies)?.let { species ->
            val cb = species.handler(callbackName)
            if (cb != null) handlers.add(resolvePriority(EventHandler(species, cb, pokemon.speciesState, { Unit }, pokemon), callbackName))
        }
        val side = pokemon.side
        // A benched Pokemon has no slot: JS reads slotConditions[position] as undefined and finds nothing.
        for ((id, slotState) in side.slotConditions.getOrNull(pokemon.position)?.entries?.toList().orEmpty()) {
            val slotCondition = dex.conditionById(id)
            callback = slotCondition.handler(callbackName)
            if (callback != null || (getKey != null && slotState.truthy(getKey))) {
                handlers.add(resolvePriority(EventHandler(slotCondition, callback, slotState,
                    { side.removeSlotCondition(pokemon, slotCondition.id) }, side), callbackName))
            }
        }
        return handlers
    }

    /** Showdown folds `Conditions[baseSpecies]` (Arceus, Silvally ...) into the species itself. */
    private fun speciesEffect(species: Species): Effect? {
        val id = Js.toID(species.baseSpecies)
        return if (id in dex.conditionIds) dex.conditionById(id) else null
    }

    fun findFieldEventHandlers(field: Field, callbackName: String, getKey: String? = null, customHolder: Pokemon? = null): List<EventHandler> {
        val handlers = ArrayList<EventHandler>()
        for ((id, state) in field.pseudoWeather.entries.toList()) {
            val pseudoWeather = dex.conditionById(id)
            val callback = pseudoWeather.handler(callbackName)
            if (callback != null || (getKey != null && state.truthy(getKey))) {
                handlers.add(resolvePriority(EventHandler(pseudoWeather, callback, state,
                    if (customHolder != null) null else { { field.removePseudoWeather(id) } }, customHolder ?: field), callbackName))
            }
        }
        val weather = field.getWeather()
        var callback = weather.handler(callbackName)
        if (callback != null || (getKey != null && field.weatherState.truthy(getKey))) {
            handlers.add(resolvePriority(EventHandler(weather, callback, field.weatherState,
                if (customHolder != null) null else { { field.clearWeather() } }, customHolder ?: field), callbackName))
        }
        val terrain = field.getTerrain()
        callback = terrain.handler(callbackName)
        if (callback != null || (getKey != null && field.terrainState.truthy(getKey))) {
            handlers.add(resolvePriority(EventHandler(terrain, callback, field.terrainState,
                if (customHolder != null) null else { { field.clearTerrain() } }, customHolder ?: field), callbackName))
        }
        return handlers
    }

    fun findSideEventHandlers(side: Side, callbackName: String, getKey: String? = null, customHolder: Pokemon? = null): List<EventHandler> {
        val handlers = ArrayList<EventHandler>()
        for ((id, state) in side.sideConditions.entries.toList()) {
            val sideCondition = dex.conditionById(id)
            val callback = sideCondition.handler(callbackName)
            if (callback != null || (getKey != null && state.truthy(getKey))) {
                handlers.add(resolvePriority(EventHandler(sideCondition, callback, state,
                    if (customHolder != null) null else { { side.removeSideCondition(id) } }, customHolder ?: side), callbackName))
            }
        }
        return handlers
    }

    fun checkMoveMakesContact(move: ActiveMove, attacker: Pokemon, defender: Pokemon, announcePads: Boolean = false): Boolean {
        if (move.flag("contact") && attacker.hasItem("protectivepads")) {
            if (announcePads) {
                add("-activate", defender, effect?.fullname)
                add("-activate", attacker, "item: Protective Pads")
            }
            return false
        }
        return move.flag("contact")
    }

    fun getAllPokemon(): List<Pokemon> = sides.flatMap { it.pokemon }

    fun getAllActive(): List<Pokemon> = sides.flatMap { side -> side.active.filter { it != null && !it.fainted }.map { it!! } }

    fun makeRequest(typeIn: String? = null) {
        val type = if (typeIn != null) {
            requestState = typeIn
            for (side in sides) side.clearChoice()
            typeIn
        } else requestState
        for (side in sides) side.activeRequest = null
        val requests = getRequests(type)
        for (i in sides.indices) sides[i].emitRequest(requests[i])
        check(!sides.all { it.isChoiceDone() }) { "Choices are done immediately after a request" }
    }

    fun clearRequest() {
        requestState = ""
        for (side in sides) {
            side.activeRequest = null
            side.clearChoice()
        }
    }

    fun getRequests(type: String): List<Side.Request> {
        val requests = arrayOfNulls<Side.Request>(sides.size)
        when (type) {
            "switch" -> for ((i, side) in sides.withIndex()) {
                if (side.pokemonLeft == 0) continue
                val table = side.active.map { it != null && Js.truthy(it.switchFlag) }
                if (table.any { it }) requests[i] = Side.Request(forceSwitch = table)
            }
            else -> for ((i, side) in sides.withIndex()) {
                if (side.pokemonLeft == 0) continue
                requests[i] = Side.Request(active = side.active.map { it?.getMoveRequestData() })
            }
        }
        return requests.map { it ?: Side.Request(wait = true) }
    }

    fun win(sideIn: Side?): Boolean {
        if (ended) return false
        val side = sideIn?.takeIf { it in sides }
        winner = side?.name ?: ""
        add("")
        if (side?.allySide != null) add("win", side.name + " & " + side.allySide!!.name)
        else if (side != null) add("win", side.name)
        else add("tie")
        updatePP()
        ended = true
        requestState = ""
        for (s in sides) s.activeRequest = null
        return true
    }

    fun tie(): Boolean = win(null)

    fun lose(side: Side): Boolean? = win(side.foe)

    fun canSwitch(side: Side): Int = possibleSwitches(side).size

    fun getRandomSwitchable(side: Side): Pokemon? {
        val candidates = possibleSwitches(side)
        return if (candidates.isEmpty()) null else sample(candidates)
    }

    fun possibleSwitches(side: Side): List<Pokemon> {
        if (side.pokemonLeft == 0) return emptyList()
        return (side.active.size until side.pokemon.size).map { side.pokemon[it] }.filter { !it.fainted }
    }

    fun swapPosition(pokemon: Pokemon, newPosition: Int, attributes: String? = null): Boolean {
        require(newPosition < pokemon.side.active.size) { "Invalid swap position" }
        val target = pokemon.side.active[newPosition]
        if (newPosition != 1 && (target == null || target.fainted)) return false
        add("swap", pokemon, newPosition, attributes ?: "")
        val side = pokemon.side
        side.pokemon[pokemon.position] = target!!
        side.pokemon[newPosition] = pokemon
        side.active[pokemon.position] = side.pokemon[pokemon.position]
        side.active[newPosition] = side.pokemon[newPosition]
        target.position = pokemon.position
        pokemon.position = newPosition
        runEvent("Swap", target, pokemon)
        runEvent("Swap", pokemon, target)
        return true
    }

    fun faint(pokemon: Pokemon, source: Pokemon? = null, effect: EffectLike? = null) {
        pokemon.faint(source, effect)
    }

    fun nextTurn() {
        turn++
        lastSuccessfulMoveThisTurn = null
        val dynamaxEnding = getAllActive().filter { Js.int(it.volatiles["dynamax"]?.get("turns")) == 3 }
        if (dynamaxEnding.size > 1) {
            updateSpeed()
            val sorted = dynamaxEnding.map { SpeedEntry(it) }.toMutableList()
            speedSort(sorted)
            for (entry in sorted) entry.pokemon.removeVolatile("dynamax")
        } else {
            for (pokemon in dynamaxEnding) pokemon.removeVolatile("dynamax")
        }
        for (side in sides) {
            for (pokemon in side.active) {
                if (pokemon == null) continue
                pokemon.moveThisTurn = ""
                pokemon.newlySwitched = false
                pokemon.moveLastTurnResult = pokemon.moveThisTurnResult
                pokemon.moveThisTurnResult = Unit
                if (turn != 1) {
                    pokemon.usedItemThisTurn = false
                    pokemon.statsRaisedThisTurn = false
                    pokemon.statsLoweredThisTurn = false
                    pokemon.hurtThisTurn = null
                }
                pokemon.maybeDisabled = false
                for (slot in pokemon.moveSlots) {
                    slot.disabled = false
                    slot.disabledSource = ""
                }
                runEvent("DisableMove", pokemon)
                for (slot in pokemon.moveSlots.toList()) {
                    val activeMove = dex.activeMove(slot.id)
                    singleEvent("DisableMove", activeMove, null, pokemon)
                    if (activeMove.flag("cantusetwice") && pokemon.lastMove?.id == slot.id) pokemon.disableMove(pokemon.lastMove!!.id)
                }
                if (pokemon.getLastAttackedBy() != null) pokemon.knownType = true
                for (i in pokemon.attackedBy.indices.reversed()) {
                    val attack = pokemon.attackedBy[i]
                    if (attack.source.isActive) attack.thisTurn = false else pokemon.attackedBy.remove(attack)
                }
                if (pokemon.terastallized == null) {
                    val seen = pokemon.illusion ?: pokemon
                    val realTypeString = seen.getTypes(true).joinToString("/")
                    if (realTypeString != seen.apparentType) {
                        add("-start", pokemon, "typechange", realTypeString, "[silent]")
                        seen.apparentType = realTypeString
                        if (pokemon.addedType.isNotEmpty()) add("-start", pokemon, "typeadd", pokemon.addedType, "[silent]")
                    }
                }
                pokemon.trapped = false
                pokemon.maybeTrapped = false
                runEvent("TrapPokemon", pokemon)
                if (!pokemon.knownType || dex.notImmune("trapped", pokemon.getTypes())) runEvent("MaybeTrapPokemon", pokemon)
                if (pokemon.fainted) continue
                pokemon.activeTurns++
            }
            side.faintedLastTurn = side.faintedThisTurn
            side.faintedThisTurn = null
        }
        if (turn >= 1000) {
            add("message", "It is turn 1000. You have hit the turn limit!")
            tie()
            return
        }
        if ((turn >= 500 && turn % 100 == 0) || (turn >= 900 && turn % 10 == 0) || turn >= 990) {
            val turnsLeft = 1000 - turn
            add("bigerror", "You will auto-tie if the battle doesn't end in ${if (turnsLeft == 1) "1 turn" else "$turnsLeft turns"} (on turn 1000).")
        }
        add("turn", turn)
        updatePP()
        makeRequest("move")
    }

    fun start() {
        check(!started) { "Battle already started" }
        started = true
        sides[1].foe = sides[0]
        sides[0].foe = sides[1]
        for (side in sides) add("teamsize", side.id, side.pokemon.size)
        add("gen", 9)
        add("tier", options.formatName)
        queue.addChoice(Action("start"))
        midTurn = true
        if (requestState.isEmpty()) go()
    }

    fun boost(boostIn: Map<String, Int>, targetIn: Pokemon? = null, sourceIn: Pokemon? = null, effectIn: EffectLike? = null,
              isSecondary: Boolean = false, isSelf: Boolean = false): Any? {
        var target = targetIn
        var source = sourceIn
        var effect = effectIn
        event?.let { e ->
            if (target == null) target = e.target as? Pokemon
            if (source == null) source = e.source as? Pokemon
            if (effect == null) effect = this.effect
        }
        val t = target ?: return 0
        if (t.hp == 0) return 0
        if (!t.isActive) return false
        if (t.side.foePokemonLeft() == 0) return false
        @Suppress("UNCHECKED_CAST")
        var boost = runEvent("ChangeBoost", t, source, effect, LinkedHashMap(boostIn)) as Map<String, Int>
        boost = t.getCappedBoost(boost)
        @Suppress("UNCHECKED_CAST")
        boost = runEvent("TryBoost", t, source, effect, LinkedHashMap(boost)) as Map<String, Int>
        var success: Boolean? = null
        var boosted = isSecondary
        for ((boostName, amount) in boost) {
            val currentBoost = linkedMapOf(boostName to amount)
            var boostBy = t.boostBy(currentBoost)
            var msg = "-boost"
            if (amount < 0 || t.boosts[boostName] == -6) {
                msg = "-unboost"
                boostBy = -boostBy
            }
            if (boostBy != 0) {
                success = true
                when (effect?.id) {
                    "bellydrum", "angerpoint" -> add("-setboost", t, "atk", t.boosts["atk"], "[from] " + effect!!.fullname)
                    else -> {
                        val e = effect
                        if (e != null) {
                            if (e.effectType == "Move") {
                                add(msg, t, boostName, boostBy)
                            } else if (e.effectType == "Item") {
                                add(msg, t, boostName, boostBy, "[from] item: " + e.name)
                            } else {
                                if (e.effectType == "Ability" && !boosted) {
                                    add("-ability", t, e.name, "boost")
                                    boosted = true
                                }
                                add(msg, t, boostName, boostBy)
                            }
                        }
                    }
                }
                runEvent("AfterEachBoost", t, source, effect, currentBoost)
            } else if (effect?.effectType == "Ability") {
                if (isSecondary || isSelf) add(msg, t, boostName, boostBy)
            } else if (!isSecondary && !isSelf) {
                add(msg, t, boostName, boostBy)
            }
        }
        runEvent("AfterBoost", t, source, effect, boost)
        if (success == true) {
            if (boost.values.any { it > 0 }) t.statsRaisedThisTurn = true
            if (boost.values.any { it < 0 }) t.statsLoweredThisTurn = true
        }
        return success
    }

    /** `spreadDamage`: `damage` entries are numbers, `true`, `false`, `null` or `Unit`; targets may be null/false. */
    fun spreadDamage(damage: List<Any?>, targetArray: List<Any?>?, source: Pokemon? = null, effectIn: Any? = null, instafaint: Boolean = false): MutableList<Any?> {
        if (targetArray == null) return mutableListOf(0)
        val retVals = MutableList<Any?>(damage.size) { Unit }
        val effect: EffectLike = when (effectIn) {
            is EffectLike -> effectIn
            is String -> dex.conditionById(effectIn)
            else -> dex.conditionById("")
        }
        for ((i, curDamage) in damage.withIndex()) {
            val target = targetArray.getOrNull(i) as? Pokemon
            var targetDamage = curDamage
            if (!(Js.truthy(targetDamage) || targetDamage == 0)) {
                retVals[i] = targetDamage
                continue
            }
            if (target == null || target.hp == 0) {
                retVals[i] = 0
                continue
            }
            if (!target.isActive) {
                retVals[i] = false
                continue
            }
            if (targetDamage != 0) targetDamage = Js.clampIntRange(targetDamage, 1)
            if (effect.id != "struggle-recoil") {
                if (effect.effectType == "Weather" && !target.runStatusImmunity(effect.id)) {
                    retVals[i] = 0
                    continue
                }
                targetDamage = runEvent("Damage", target, source, effect, targetDamage, true)
                if (!(Js.truthy(targetDamage) || targetDamage == 0)) {
                    retVals[i] = if (curDamage == true) Unit else targetDamage
                    continue
                }
            }
            if (targetDamage != 0) targetDamage = Js.clampIntRange(targetDamage, 1)
            val dealt = target.damage(Js.num(targetDamage), source, effect)
            retVals[i] = dealt
            if (dealt != 0) target.hurtThisTurn = target.hp
            if (source != null && effect.effectType == "Move") source.lastDamage = dealt
            val name = if (effect.fullname == "tox") "psn" else effect.fullname
            when (effect.id) {
                "partiallytrapped" -> add("-damage", target, target.getHealth, "[from] " + effectState.sourceEffect?.fullname, "[partiallytrapped]")
                "powder" -> add("-damage", target, target.getHealth, "[silent]")
                "confused" -> add("-damage", target, target.getHealth, "[from] confusion")
                else -> if (effect.effectType == "Move" || name.isEmpty()) {
                    add("-damage", target, target.getHealth)
                } else if (source != null && (source !== target || effect.effectType == "Ability")) {
                    add("-damage", target, target.getHealth, "[from] $name", "[of] $source")
                } else {
                    add("-damage", target, target.getHealth, "[from] $name")
                }
            }
            if (dealt != 0 && effect is ActiveMove) {
                effect.drain?.let { drain ->
                    if (source != null) {
                        val amount = Math.round(dealt.toDouble() * drain[0] / drain[1]).toInt()
                        heal(amount, source, target, dex.conditionById("drain"))
                    }
                }
            }
        }
        if (instafaint) {
            for ((i, t) in targetArray.withIndex()) {
                if (!Js.truthy(retVals[i]) || t !is Pokemon) continue
                if (t.hp <= 0) faintMessages(true)
            }
        }
        return retVals
    }

    fun damage(amount: Number, targetIn: Pokemon? = null, sourceIn: Pokemon? = null, effectIn: Any? = null, instafaint: Boolean = false): Any? {
        var target = targetIn
        var source = sourceIn
        var effect = effectIn
        event?.let { e ->
            if (target == null) target = e.target as? Pokemon
            if (source == null) source = e.source as? Pokemon
            if (effect == null) effect = this.effect
        }
        return spreadDamage(listOf(Js.number(amount.toDouble())), listOf(target), source, effect, instafaint)[0]
    }

    fun directDamage(amountIn: Number, targetIn: Pokemon? = null, sourceIn: Pokemon? = null, effectIn: EffectLike? = null): Int {
        var target = targetIn
        var source = sourceIn
        var effect = effectIn
        event?.let { e ->
            if (target == null) target = e.target as? Pokemon
            if (source == null) source = e.source as? Pokemon
            if (effect == null) effect = this.effect
        }
        val t = target ?: return 0
        if (t.hp == 0) return 0
        if (amountIn.toDouble() == 0.0) return 0
        val amount = Js.clampIntRange(Js.number(amountIn.toDouble()), 1).toDouble()
        val dealt = t.damage(amount, source, effect)
        when (effect?.id) {
            "strugglerecoil" -> add("-damage", t, t.getHealth, "[from] recoil")
            "confusion" -> add("-damage", t, t.getHealth, "[from] confusion")
            else -> add("-damage", t, t.getHealth)
        }
        if (t.fainted) faint(t)
        return dealt
    }

    fun heal(amountIn: Number, targetIn: Pokemon? = null, sourceIn: Pokemon? = null, effectIn: EffectLike? = null): Any? {
        var target = targetIn
        var source = sourceIn
        var effect = effectIn
        event?.let { e ->
            if (target == null) target = e.target as? Pokemon
            if (source == null) source = e.source as? Pokemon
            if (effect == null) effect = this.effect
        }
        var amount = amountIn.toDouble()
        if (amount != 0.0 && amount <= 1) amount = 1.0
        var healed: Any? = Js.trunc(amount)
        healed = runEvent("TryHeal", target, source, effect, healed)
        if (!Js.truthy(healed)) return healed
        val t = target ?: return false
        if (t.hp == 0) return false
        if (!t.isActive) return false
        if (t.hp >= t.maxhp) return false
        val finalDamage = t.heal(Js.num(healed), source, effect)
        when (effect?.id) {
            "leechseed", "rest" -> add("-heal", t, t.getHealth, "[silent]")
            "drain" -> add("-heal", t, t.getHealth, "[from] drain", "[of] $source")
            "wish" -> Unit
            "zpower" -> add("-heal", t, t.getHealth, "[zeffect]")
            else -> {
                val e = effect
                if (e != null) {
                    if (e.effectType == "Move") add("-heal", t, t.getHealth)
                    else if (source != null && source !== t) add("-heal", t, t.getHealth, "[from] " + e.fullname, "[of] $source")
                    else add("-heal", t, t.getHealth, "[from] " + e.fullname)
                }
            }
        }
        runEvent("Heal", t, source, effect, finalDamage)
        return finalDamage
    }

    fun chain(previousMod: Double, nextMod: Double): Double {
        val prev = Js.trunc(previousMod * 4096).toLong()
        val next = Js.trunc(nextMod * 4096).toLong()
        return (((prev * next + 2048) shr 12).toInt()) / 4096.0
    }

    fun chainModify(numerator: Number, denominator: Number = 1) {
        val e = event ?: return
        val previousMod = Js.trunc(e.modifier * 4096).toLong()
        val nextMod = Js.trunc(numerator.toDouble() * 4096 / (if (denominator.toDouble() == 0.0) 1.0 else denominator.toDouble())).toLong()
        e.modifier = jsShiftRight12(previousMod * nextMod + 2048) / 4096.0
    }

    /** `(x + 2048) >> 12` on a JS number: `>>` works on the low 32 bits as a signed integer. */
    private fun jsShiftRight12(value: Long): Int = (value.toInt()) shr 12

    fun modify(value: Int, numerator: Number, denominator: Number = 1): Int {
        val den = if (denominator.toDouble() == 0.0) 1.0 else denominator.toDouble()
        val modifier = Js.trunc(numerator.toDouble() * 4096 / den)
        return Js.trunc((Js.trunc(value.toDouble() * modifier).toDouble() + 2048 - 1) / 4096)
    }

    fun spreadModify(baseStats: Map<String, Int>, set: PokemonSet): LinkedHashMap<String, Int> {
        val stats = linkedMapOf("atk" to 10, "def" to 10, "spa" to 10, "spd" to 10, "spe" to 10)
        for (statName in stats.keys) {
            val stat = baseStats[statName] ?: 0
            val iv = set.ivs[statName] ?: 31
            val ev = set.evs[statName] ?: 0
            stats[statName] = Js.trunc(Js.trunc((2 * stat + iv + ev / 4).toDouble()).toDouble() * set.level / 100 + 5)
        }
        if ("hp" in baseStats) {
            val stat = baseStats.getValue("hp")
            val iv = set.ivs["hp"] ?: 31
            val ev = set.evs["hp"] ?: 0
            stats["hp"] = Js.trunc(Js.trunc((2 * stat + iv + ev / 4 + 100).toDouble()).toDouble() * set.level / 100 + 10)
        }
        return natureModify(stats, set)
    }

    fun natureModify(stats: LinkedHashMap<String, Int>, set: PokemonSet): LinkedHashMap<String, Int> {
        val nature = dex.nature(set.nature) ?: return stats
        nature.plus?.let { s -> stats[s] = Js.trunc(Js.trunc(stats.getValue(s) * 110.0, 16).toDouble() / 100) }
        nature.minus?.let { s -> stats[s] = Js.trunc(Js.trunc(stats.getValue(s) * 90.0, 16).toDouble() / 100) }
        return stats
    }

    fun finalModify(relayVar: Int): Int {
        val e = event!!
        val result = modify(relayVar, e.modifier)
        e.modifier = 1.0
        return result
    }

    fun randomizer(baseDamage: Int): Int {
        val actual = Js.trunc(Js.trunc(baseDamage.toDouble() * (100 - random(16))).toDouble() / 100)
        return tracer?.randomizer(this, baseDamage, actual) ?: actual
    }

    fun validTargetLoc(targetLoc: Int, source: Pokemon, targetType: String): Boolean {
        if (targetLoc == 0) return true
        val numSlots = activePerHalf
        val sourceLoc = source.getLocOf(source)
        if (Math.abs(targetLoc) > numSlots) return false
        val isSelf = sourceLoc == targetLoc
        val isFoe = if (gameType == "freeforall") !isSelf else targetLoc > 0
        val acrossFromTargetLoc = -(numSlots + 1 - targetLoc)
        val isAdjacent = if (targetLoc > 0) Math.abs(acrossFromTargetLoc - sourceLoc) <= 1 else Math.abs(targetLoc - sourceLoc) == 1
        if (gameType == "freeforall" && targetType == "adjacentAlly") return isAdjacent
        return when (targetType) {
            "randomNormal", "scripted", "normal" -> isAdjacent
            "adjacentAlly" -> isAdjacent && !isFoe
            "adjacentAllyOrSelf" -> (isAdjacent && !isFoe) || isSelf
            "adjacentFoe" -> isAdjacent && isFoe
            "any" -> !isSelf
            else -> false
        }
    }

    fun validTarget(target: Pokemon, source: Pokemon, targetType: String): Boolean =
        validTargetLoc(source.getLocOf(target), source, targetType)

    fun getTarget(pokemon: Pokemon, move: ActiveMove, targetLoc: Int, originalTarget: Pokemon? = null): Pokemon? {
        var tracksTarget = move.tracksTarget
        if (pokemon.hasAbility(listOf("stalwart", "propellertail"))) tracksTarget = true
        if (tracksTarget && originalTarget != null && originalTarget.isActive) return originalTarget
        if (move.smartTarget == true) {
            val cur = pokemon.getAtLoc(targetLoc)
            return if (cur != null && !cur.fainted) cur else getRandomTarget(pokemon, move)
        }
        val selfLoc = pokemon.getLocOf(pokemon)
        if (move.target in listOf("adjacentAlly", "any", "normal") && targetLoc == selfLoc && pokemon.volatiles["twoturnmove"] == null &&
            pokemon.volatiles["iceball"] == null && pokemon.volatiles["rollout"] == null) {
            return if (move.flag("futuremove")) pokemon else null
        }
        if (move.target != "randomNormal" && validTargetLoc(targetLoc, pokemon, move.target)) {
            val target = pokemon.getAtLoc(targetLoc)
            if (target != null && target.fainted) {
                if (gameType == "freeforall") return target
                if (target.isAlly(pokemon)) return target
            }
            if (target != null && !target.fainted) return target
        }
        return getRandomTarget(pokemon, move)
    }

    fun getRandomTarget(pokemon: Pokemon, move: ActiveMove): Pokemon? {
        if (move.target in listOf("self", "all", "allySide", "allyTeam", "adjacentAllyOrSelf")) return pokemon
        if (move.target == "adjacentAlly") {
            if (gameType == "singles") return null
            val adjacentAllies = pokemon.adjacentAllies()
            return if (adjacentAllies.isNotEmpty()) sample(adjacentAllies) else null
        }
        if (gameType == "singles") return pokemon.side.foe.active[0]
        if (activePerHalf > 2 && move.target in listOf("adjacentFoe", "normal", "randomNormal")) {
            val adjacentFoes = pokemon.adjacentFoes()
            if (adjacentFoes.isNotEmpty()) return sample(adjacentFoes)
            return pokemon.side.foe.active[pokemon.side.foe.active.size - 1 - pokemon.position]
        }
        return pokemon.side.randomFoe() ?: pokemon.side.foe.active[0]
    }

    fun checkFainted() {
        for (side in sides) {
            for (pokemon in side.active) {
                if (pokemon != null && pokemon.fainted) {
                    pokemon.status = "fnt"
                    pokemon.switchFlag = true
                }
            }
        }
    }

    fun updatePP() {
        if (!logEnabled) return
        for (side in sides) for (pokemon in side.pokemon) pokemon.updatePP()
    }

    fun faintMessages(lastFirst: Boolean = false, forceCheck: Boolean = false, checkWinIn: Boolean = true): Boolean {
        if (ended) return false
        var checkWin = checkWinIn
        val length = faintQueue.size
        if (length == 0) {
            if (forceCheck && checkWin()) return true
            return false
        }
        if (lastFirst) {
            val last = faintQueue.removeAt(faintQueue.size - 1)
            faintQueue.add(0, last)
        }
        var faintData: FaintData? = null
        while (faintQueue.isNotEmpty()) {
            val faintQueueLeft = faintQueue.size
            faintData = faintQueue.removeAt(0)
            val pokemon = faintData.target
            if (!pokemon.fainted && Js.truthy(runEvent("BeforeFaint", pokemon, faintData.source, faintData.effect))) {
                add("faint", pokemon)
                if (pokemon.side.pokemonLeft != 0) pokemon.side.pokemonLeft--
                if (pokemon.side.totalFainted < 100) pokemon.side.totalFainted++
                runEvent("Faint", pokemon, faintData.source, faintData.effect)
                singleEvent("End", pokemon.getAbility(), pokemon.abilityState, pokemon)
                pokemon.clearVolatile(false)
                pokemon.fainted = true
                pokemon.illusion = null
                pokemon.isActive = false
                pokemon.isStarted = false
                pokemon.terastallized = null
                pokemon.side.faintedThisTurn = pokemon
                if (faintQueue.size >= faintQueueLeft) checkWin = true
            }
        }
        if (checkWin && checkWin(faintData)) return true
        if (faintData != null && length != 0) runEvent("AfterFaint", faintData.target, faintData.source, faintData.effect, length)
        return false
    }

    fun checkWin(faintData: FaintData? = null): Boolean {
        val team1 = sides[0].pokemonLeft
        val team2 = sides[1].pokemonLeft
        if (team1 == 0 && team2 == 0) {
            win(faintData?.target?.side)
            return true
        }
        for (side in sides) {
            if (side.foePokemonLeft() == 0) {
                win(side)
                return true
            }
        }
        return false
    }

    fun getActionSpeed(action: Action) {
        if (action.choice == "move") {
            var move = action.move!!
            val pokemon = action.pokemon!!
            action.maxMove?.let {
                val max = actions.getActiveMaxMove(action.move!!, pokemon)
                if (Js.truthy(max.isMax)) move = max
            }
            var priority: Any? = dex.move(move.id)?.priority ?: move.priority
            priority = singleEvent("ModifyPriority", move, null, pokemon, null, null, priority)
            priority = runEvent("ModifyPriority", pokemon, null, move, priority)
            action.priority = Js.num(priority) + action.fractionalPriority
            action.move!!.priority = Js.int(priority)
        }
        action.speed = action.pokemon?.getActionSpeed()?.toDouble() ?: 1.0
    }

    fun runAction(action: Action): Boolean {
        val pokemonOriginalHP = action.pokemon?.hp
        var residualPokemon: List<Pair<Pokemon, Int>> = emptyList()
        when (action.choice) {
            "start" -> {
                for (side in sides) if (side.pokemonLeft != 0) side.pokemonLeft = side.pokemon.count { !it.fainted }
                add("start")
                for (side in sides) {
                    for (i in side.active.indices) {
                        if (side.pokemonLeft == 0) {
                            side.active[i] = side.pokemon[i]
                            side.active[i]!!.fainted = true
                            side.active[i]!!.hp = 0
                        } else {
                            actions.switchIn(side.pokemon[i], i)
                        }
                    }
                }
                for (pokemon in getAllPokemon()) {
                    singleEvent("Start", dex.conditionById(pokemon.species.id), pokemon.speciesState, pokemon)
                }
                midTurn = true
            }
            "move" -> {
                val pokemon = action.pokemon!!
                if (!pokemon.isActive) return false
                if (pokemon.fainted) return false
                actions.runMove(action.move!!, pokemon, action.targetLoc, action.sourceEffect, action.maxMove, action.originalTarget)
            }
            "megaEvo" -> actions.runMegaEvo(action.pokemon!!)
            "runDynamax" -> {
                val pokemon = action.pokemon!!
                pokemon.addVolatile("dynamax")
                pokemon.side.dynamaxUsed = true
                pokemon.canTerastallize = null
                pokemon.side.allySide?.dynamaxUsed = true
            }
            "terastallize" -> actions.terastallize(action.pokemon!!)
            "beforeTurnMove" -> {
                val pokemon = action.pokemon!!
                if (!pokemon.isActive) return false
                if (pokemon.fainted) return false
                val target = getTarget(pokemon, action.move!!, action.targetLoc) ?: return false
                callback(action.move!!, "beforeTurnCallback", pokemon, target, null)
            }
            "priorityChargeMove" -> {
                val pokemon = action.pokemon!!
                if (!pokemon.isActive) return false
                if (pokemon.fainted) return false
                callback(action.move!!, "priorityChargeCallback", pokemon, null, null)
            }
            "event" -> runEvent(action.event!!, action.pokemon)
            "pass" -> return false
            "instaswitch", "switch" -> {
                val pokemon = action.pokemon!!
                if (action.choice == "switch" && pokemon.status.isNotEmpty()) {
                    singleEvent("CheckShow", dex.ability("naturalcure"), null, pokemon)
                }
                if (actions.switchIn(action.target!!, pokemon.position, action.sourceEffect) == "pursuitfaint") {
                    hint("A Pokemon can't switch between when it runs out of HP and when it faints")
                }
            }
            "revivalblessing" -> {
                val pokemon = action.pokemon!!
                val target = action.target!!
                pokemon.side.pokemonLeft++
                if (target.position < pokemon.side.active.size) {
                    queue.addChoice(Action("instaswitch", pokemon = target, target = target))
                }
                target.fainted = false
                target.faintQueued = false
                target.subFainted = false
                target.status = ""
                target.hp = 1
                target.sethp(target.maxhp / 2.0)
                add("-heal", target, target.getHealth, "[from] move: Revival Blessing")
                pokemon.side.removeSlotCondition(pokemon, "revivalblessing")
            }
            "runUnnerve" -> {
                val pokemon = action.pokemon!!
                singleEvent("PreStart", pokemon.getAbility(), pokemon.abilityState, pokemon)
            }
            "runSwitch" -> actions.runSwitch(action.pokemon!!)
            "runPrimal" -> {
                val pokemon = action.pokemon!!
                if (!pokemon.transformed) singleEvent("Primal", pokemon.getItem(), pokemon.itemState, pokemon)
            }
            "shift" -> {
                val pokemon = action.pokemon!!
                if (!pokemon.isActive) return false
                if (pokemon.fainted) return false
                swapPosition(pokemon, 1)
            }
            "beforeTurn" -> eachEvent("BeforeTurn")
            "residual" -> {
                add("")
                clearActiveMove(true)
                updateSpeed()
                residualPokemon = getAllActive().map { it to it.getUndynamaxedHP() }
                residualEvent("Residual")
                add("upkeep")
            }
        }
        for (side in sides) {
            for (pokemon in side.active) {
                if (pokemon != null && pokemon.forceSwitchFlag) {
                    if (pokemon.hp != 0) actions.dragIn(pokemon.side, pokemon.position)
                    pokemon.forceSwitchFlag = false
                }
            }
        }
        clearActiveMove()
        faintMessages()
        if (ended) return true
        if (queue.peek() == null) {
            checkFainted()
        } else if (queue.peek()?.choice == "instaswitch") {
            return false
        }
        eachEvent("Update")
        for ((pokemon, originalHP) in residualPokemon) {
            val maxhp = pokemon.getUndynamaxedHP(pokemon.maxhp)
            if (pokemon.hp != 0 && pokemon.getUndynamaxedHP() <= maxhp / 2.0 && originalHP > maxhp / 2.0) runEvent("EmergencyExit", pokemon)
        }
        if (action.choice == "runSwitch") {
            val pokemon = action.pokemon!!
            if (pokemon.hp != 0 && pokemon.hp <= pokemon.maxhp / 2.0 && (pokemonOriginalHP ?: 0) > pokemon.maxhp / 2.0) {
                runEvent("EmergencyExit", pokemon)
            }
        }
        val switches = sides.map { side -> side.active.any { it != null && Js.truthy(it.switchFlag) } }.toMutableList()
        for (i in sides.indices) {
            var reviveSwitch = false
            if (switches[i] && canSwitch(sides[i]) == 0) {
                for (pokemon in sides[i].active) {
                    if (pokemon == null) continue
                    if (sides[i].slotConditions[pokemon.position]["revivalblessing"] != null) {
                        reviveSwitch = true
                        continue
                    }
                    pokemon.switchFlag = false
                }
                if (!reviveSwitch) switches[i] = false
            } else if (switches[i]) {
                for (pokemon in sides[i].active) {
                    if (pokemon != null && pokemon.hp != 0 && Js.truthy(pokemon.switchFlag) && pokemon.switchFlag != "revivalblessing" &&
                        !pokemon.skipBeforeSwitchOutEventFlag) {
                        runEvent("BeforeSwitchOut", pokemon)
                        pokemon.skipBeforeSwitchOutEventFlag = true
                        faintMessages()
                        if (ended) return true
                        if (pokemon.fainted) switches[i] = sides[i].active.any { it != null && Js.truthy(it.switchFlag) }
                    }
                }
            }
        }
        for (playerSwitch in switches) {
            if (playerSwitch) {
                makeRequest("switch")
                return true
            }
        }
        val next = queue.peek()?.choice
        if (next == "move" || next == "runDynamax") {
            updateSpeed()
            for (queued in queue.list) if (queued.pokemon != null) getActionSpeed(queued)
            queue.sort()
        }
        return false
    }

    fun go() {
        add("")
        add("t:", 0)
        if (requestState.isNotEmpty()) requestState = ""
        if (!midTurn) {
            queue.insertChoice(Action("beforeTurn"))
            queue.addChoice(Action("residual"))
            midTurn = true
        }
        while (true) {
            val action = queue.shift() ?: break
            runAction(action)
            if (requestState.isNotEmpty() || ended) return
        }
        nextTurn()
        midTurn = false
        queue.clear()
    }

    fun choose(sideId: String, input: String): Boolean {
        val side = getSide(sideId)
        if (!side.choose(input)) return false
        if (!side.isChoiceDone()) {
            side.emitChoiceError("Incomplete choice: $input - missing other pokemon")
            return false
        }
        if (allChoicesDone()) commitDecisions()
        return true
    }

    /** `battle.makeChoices(p1, p2)`: an empty string leaves that side's choice alone (a waiting side). */
    fun makeChoices(vararg inputs: String) {
        for ((i, input) in inputs.withIndex()) {
            if (input.isNotEmpty()) sides[i].choose(input)
        }
        commitDecisions()
    }

    fun commitDecisions() {
        updateSpeed()
        val oldQueue = queue.list
        queue.clear()
        check(allChoicesDone()) { "Not all choices done" }
        for (side in sides) queue.addChoice(side.choice.actions)
        clearRequest()
        queue.sort()
        queue.list.addAll(oldQueue)
        requestState = ""
        for (side in sides) side.activeRequest = null
        go()
    }

    fun allChoicesDone(): Boolean {
        var total = 0
        for (side in sides) {
            if (side.isChoiceDone()) {
                side.choice.cantUndo = true
                total++
            }
        }
        return total >= sides.size
    }

    fun hint(hint: String, once: Boolean = false) {
        if (hint in hints) return
        add("-hint", hint)
        if (once) hints.add(hint)
    }

    private fun part(value: Any?): String = when (value) {
        is Pokemon -> value.toString()
        is Side -> value.toString()
        is EffectLike -> value.name
        else -> Js.str(value)
    }

    fun add(vararg parts: Any?) {
        tracer?.let { t ->
            val kind = parts.getOrNull(0)
            val target = parts.getOrNull(1)
            if ((kind == "-damage" || kind == "-heal") && target is Pokemon) t.hpLine(this, kind as String, target, parts.drop(3).map { part(it) })
        }
        if (!logEnabled) return
        if (parts.none { it is SplitPart }) {
            log.add("|" + parts.joinToString("|") { part(it) })
            return
        }
        var side: String? = null
        val secret = ArrayList<String>()
        val shared = ArrayList<String>()
        for (p in parts) {
            if (p is SplitPart) {
                val split = p.produce()
                check(side == null || side == split.side) { "Multiple sides passed to add" }
                side = split.side
                secret.add(split.secret)
                shared.add(split.shared)
            } else {
                secret.add(part(p))
                shared.add(part(p))
            }
        }
        log.add("|split|$side")
        log.add("|" + secret.joinToString("|"))
        log.add("|" + shared.joinToString("|"))
    }

    fun addMove(vararg args: Any?) {
        tracer?.let { t ->
            val pokemon = args.getOrNull(1)
            if (args.getOrNull(0) == "move" && pokemon is Pokemon) t.move(this, pokemon, Js.str(args.getOrNull(2)))
        }
        if (!logEnabled) return
        lastMoveLine = log.size
        log.add("|" + args.joinToString("|") { part(it) })
    }

    fun attrLastMove(vararg args: String) {
        if (!logEnabled || lastMoveLine < 0) return
        if (log[lastMoveLine].startsWith("|-anim|")) {
            if ("[still]" in args) {
                log.removeAt(lastMoveLine)
                lastMoveLine = -1
                return
            }
        } else if ("[still]" in args) {
            val parts = log[lastMoveLine].split("|").toMutableList()
            while (parts.size <= 4) parts.add("")
            parts[4] = ""
            log[lastMoveLine] = parts.joinToString("|")
        }
        log[lastMoveLine] = log[lastMoveLine] + "|" + args.joinToString("|")
    }

    fun retargetLastMove(newTarget: Pokemon) {
        if (!logEnabled || lastMoveLine < 0) return
        val parts = log[lastMoveLine].split("|").toMutableList()
        while (parts.size <= 4) parts.add("")
        parts[4] = newTarget.toString()
        log[lastMoveLine] = parts.joinToString("|")
    }

    fun setPlayer(slot: String, name: String, team: List<PokemonSet>) {
        val slotNum = slot.substring(1).toInt() - 1
        check(sides.size == slotNum) { "Players join in order" }
        val side = Side(name, this, slotNum, team)
        sides.add(side)
        add("player", side.id, side.name, "", "")
        if (sides.size == 2 && !started && !options.deferStart) start()
    }

    fun getSide(sideId: String): Side = sides[sideId.substring(1).toInt() - 1]

    companion object {
        val EMPTY_EFFECT: Effect = Effect("", "", "", com.google.gson.JsonObject(), "", emptySet())
        val TOP_EVENT = BattleEvent("", null, null, null)
        private val LEFT_TO_RIGHT_EVENTS = setOf("Invulnerability", "TryHit", "DamagingHit", "EntryHazard")
        private val UNPREFIXED_EVENTS = setOf("BeforeTurn", "Update", "Weather", "WeatherChange", "TerrainChange")
        private const val DEFAULT_ORDER = 4294967296.0

        fun comparePriority(a: Prioritized, b: Prioritized): Int {
            val order = -((b.sortOrder?.toDouble() ?: DEFAULT_ORDER) - (a.sortOrder?.toDouble() ?: DEFAULT_ORDER))
            if (order != 0.0) return sign(order)
            val priority = b.sortPriority - a.sortPriority
            if (priority != 0.0) return sign(priority)
            val speed = b.sortSpeed - a.sortSpeed
            if (speed != 0.0) return sign(speed)
            val subOrder = -(b.sortSubOrder - a.sortSubOrder)
            return subOrder.coerceIn(-1, 1)
        }

        fun compareRedirectOrder(a: EventHandler, b: EventHandler): Int {
            val priority = b.priority - a.priority
            if (priority != 0.0) return sign(priority)
            val speed = b.speed - a.speed
            if (speed != 0.0) return sign(speed)
            val ha = a.effectHolder as? Pokemon
            val hb = b.effectHolder as? Pokemon
            if (ha != null && hb != null) return -(hb.abilityOrder - ha.abilityOrder).coerceIn(-1, 1)
            return 0
        }

        fun compareLeftToRightOrder(a: EventHandler, b: EventHandler): Int {
            val order = -((b.order?.toDouble() ?: DEFAULT_ORDER) - (a.order?.toDouble() ?: DEFAULT_ORDER))
            if (order != 0.0) return sign(order)
            val priority = b.priority - a.priority
            if (priority != 0.0) return sign(priority)
            return -((b.index ?: 0) - (a.index ?: 0)).coerceIn(-1, 1)
        }

        private fun sign(value: Double): Int = if (value > 0) 1 else if (value < 0) -1 else 0
    }
}

/** `dex.getActiveMove(name)`: a fresh mutable copy of the move's data. */
fun EngineDex.activeMove(name: String): ActiveMove = ActiveMove(moveOrPlaceholder(name))
