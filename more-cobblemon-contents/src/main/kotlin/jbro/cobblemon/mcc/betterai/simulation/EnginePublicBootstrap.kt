package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove
import kotlin.math.roundToInt

/** Applies a validated public seed after constructing sets, without re-running entry effects. */
internal object EnginePublicBootstrap {
    fun apply(battle: Battle, seed: NativePublicBootstrap) {
        val byId = seed.pokemon.associateBy { it.uuid }
        battle.turn = seed.turn
        for (side in battle.sides) {
            side.pokemon.sortBy { byId.getValue(it.uuid).activeSlot ?: Int.MAX_VALUE }
            side.active.indices.forEach { side.active[it] = null }
            for ((position, mon) in side.pokemon.withIndex()) {
                val value = byId.getValue(mon.uuid)
                mon.position = position
                mon.hp = if (value.publicPercentHp && value.hpFraction in 0.01..0.99) {
                    // A hidden hypothesis owns its integer HP; match the publicly rounded percent.
                    val compatible = (1 until mon.maxhp).filter {
                        kotlin.math.abs(NativeShowdownPublicHp.fraction(it, mon.maxhp) - value.hpFraction) < 1e-9
                    }
                    require(compatible.isNotEmpty()) { "Public HP cannot be represented by hypothesis ${mon.uuid}" }
                    compatible[compatible.size / 2]
                } else (mon.maxhp * value.hpFraction).roundToInt().coerceIn(0, mon.maxhp)
                mon.fainted = mon.hp == 0
                mon.faintQueued = false
                mon.isActive = value.activeSlot != null && !mon.fainted
                mon.isStarted = mon.isActive
                mon.status = value.status
                mon.statusState = EffectState(value.status).also { it.target = mon }
                mon.volatiles.clear()
                value.substituteHpFraction?.let { fraction ->
                    mon.volatiles["substitute"] = EffectState("substitute").also {
                        it.target = mon
                        it["hp"] = (fraction * mon.maxhp).roundToInt().coerceAtLeast(1)
                    }
                }
                mon.boosts.keys.toList().forEach { mon.boosts[it] = 0 }
                value.boosts.forEach { (stat, stage) -> mon.boosts[stat] = stage }
                value.ability?.let { ability ->
                    mon.ability = ability
                    mon.abilityState = EffectState(ability).also { it.target = mon }
                }
                value.item?.let { item ->
                    mon.item = item
                    mon.itemState = EffectState(item).also { it.target = mon }
                }
                mon.activeTurns = value.activeTurns
                mon.activeMoveActions = value.activeMoveActions
                mon.lastMove = value.lastMoveId?.let(battle.dex::activeMove)
                mon.lastMoveUsed = mon.lastMove
                if (mon.item in setOf("choiceband", "choicespecs", "choicescarf") && mon.lastMove != null) {
                    mon.volatiles["choicelock"] = EffectState("choicelock").also {
                        it.target = mon
                        it["move"] = mon.lastMove!!.id
                    }
                }
                mon.moveThisTurn = ""
                mon.newlySwitched = false
                mon.switchFlag = false
                mon.forceSwitchFlag = false
                value.movePp.forEach { (move, pp) ->
                    mon.moveSlots.firstOrNull { it.id == Js.toID(move) }?.let {
                        // Exact own PP may include PP Ups, unavailable in the set contract.
                        it.maxpp = maxOf(it.maxpp, pp)
                        it.pp = pp
                    }
                }
                if (mon.isActive) side.active[value.activeSlot!!] = mon
            }
            side.pokemonLeft = side.pokemon.count { !it.fainted }
            side.totalFainted = side.pokemon.count { it.fainted }
            side.sideConditions.clear()
            val effects = if (side.n == 0) seed.p1SideConditions else seed.p2SideConditions
            effects.forEach { effect -> side.sideConditions[effect.id] = state(effect).also { it.target = side } }
        }
        battle.field.weather = seed.weather?.id.orEmpty()
        battle.field.weatherState = seed.weather?.let(::state) ?: EffectState("")
        battle.field.terrain = seed.terrain?.id.orEmpty()
        battle.field.terrainState = seed.terrain?.let(::state) ?: EffectState("")
        battle.field.pseudoWeather.clear()
        seed.pseudoWeather.forEach { battle.field.pseudoWeather[it.id] = state(it) }
        for (mon in battle.sides.flatMap { it.active.filterNotNull() }) {
            mon.maybeDisabled = false
            mon.moveSlots.forEach { it.disabled = false; it.disabledSource = "" }
            battle.runEvent("DisableMove", mon)
            for (slot in mon.moveSlots.toList()) {
                val move = battle.dex.activeMove(slot.id)
                battle.singleEvent("DisableMove", move, null, mon)
                if (move.flag("cantusetwice") && mon.lastMove?.id == slot.id) mon.disableMove(slot.id)
            }
            mon.trapped = false
            mon.maybeTrapped = false
            battle.runEvent("TrapPokemon", mon)
            battle.runEvent("MaybeTrapPokemon", mon)
        }
        battle.makeRequest("move")
    }

    private fun state(value: NativeTimedEffectFrame) = EffectState(value.id).also {
        it.duration = value.remainingTurns
        value.stacks?.let { layers -> it["layers"] = layers }
    }
}
