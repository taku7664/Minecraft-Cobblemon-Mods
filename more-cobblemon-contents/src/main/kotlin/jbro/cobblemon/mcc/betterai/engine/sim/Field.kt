package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike

/** Port of `sim/field.js`. */
class Field(private val battle: Battle) {
    var weather: String = ""
    var weatherState: EffectState = EffectState("")
    var terrain: String = ""
    var terrainState: EffectState = EffectState("")
    val pseudoWeather: LinkedHashMap<String, EffectState> = LinkedHashMap()

    override fun toString(): String = ""

    fun setWeather(statusName: String, sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null): Any? {
        val status = battle.dex.condition(statusName)
        val sourceEffect = sourceEffectIn ?: battle.effect
        val source = sourceIn ?: battle.event?.target as? Pokemon
        if (weather == status.id) {
            if (sourceEffect != null && sourceEffect.effectType == "Ability") return false
            return false
        }
        if (source != null) {
            val result = battle.runEvent("SetWeather", source, source, status)
            if (!Js.truthy(result)) {
                if (result == false) {
                    if (sourceEffect != null && Js.truthy(sourceEffect.data("weather"))) {
                        battle.add("-fail", source, sourceEffect, "[from] $weather")
                    } else if (sourceEffect != null && sourceEffect.effectType == "Ability") {
                        battle.add("-ability", source, sourceEffect, "[from] $weather", "[fail]")
                    }
                }
                return null
            }
        }
        val prevWeather = weather
        val prevWeatherState = weatherState
        weather = status.id
        weatherState = EffectState(status.id)
        if (source != null) {
            weatherState.source = source
            weatherState.sourceSlot = source.getSlot()
        }
        status.duration?.takeIf { it != 0 }?.let { weatherState.duration = it }
        if (status.declares("durationCallback")) {
            requireNotNull(source) { "setting weather without a source" }
            weatherState.duration = Js.int(battle.callback(status, "durationCallback", source, source, sourceEffect))
        }
        if (!Js.truthy(battle.singleEvent("FieldStart", status, weatherState, this, source, sourceEffect))) {
            weather = prevWeather
            weatherState = prevWeatherState
            return false
        }
        battle.eachEvent("WeatherChange", sourceEffect)
        return true
    }

    fun clearWeather(): Boolean {
        if (weather.isEmpty()) return false
        val prev = getWeather()
        battle.singleEvent("FieldEnd", prev, weatherState, this)
        weather = ""
        weatherState = EffectState("")
        battle.eachEvent("WeatherChange")
        return true
    }

    fun effectiveWeather(): String = if (suppressingWeather()) "" else weather

    fun suppressingWeather(): Boolean {
        for (side in battle.sides) {
            for (pokemon in side.active) {
                if (pokemon != null && !pokemon.fainted && !pokemon.ignoringAbility() && pokemon.getAbility().bool("suppressWeather")) return true
            }
        }
        return false
    }

    fun isWeather(weather: String): Boolean = effectiveWeather() == Js.toID(weather)

    fun isWeather(weathers: List<String>): Boolean = effectiveWeather() in weathers.map { Js.toID(it) }

    fun getWeather(): Effect = battle.dex.conditionById(weather)

    fun setTerrain(statusName: String, sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null): Boolean {
        val status = battle.dex.condition(statusName)
        val sourceEffect = sourceEffectIn ?: battle.effect
        val source = sourceIn ?: battle.event?.target as? Pokemon ?: error("setting terrain without a source")
        if (terrain == status.id) return false
        val prevTerrain = terrain
        val prevTerrainState = terrainState
        terrain = status.id
        terrainState = EffectState(status.id).also {
            it.source = source
            it.sourceSlot = source.getSlot()
            it.duration = status.duration
        }
        if (status.declares("durationCallback")) {
            terrainState.duration = Js.int(battle.callback(status, "durationCallback", source, source, sourceEffect))
        }
        if (!Js.truthy(battle.singleEvent("FieldStart", status, terrainState, this, source, sourceEffect))) {
            terrain = prevTerrain
            terrainState = prevTerrainState
            return false
        }
        battle.eachEvent("TerrainChange", sourceEffect)
        return true
    }

    fun clearTerrain(): Boolean {
        if (terrain.isEmpty()) return false
        val prev = getTerrain()
        battle.singleEvent("FieldEnd", prev, terrainState, this)
        terrain = ""
        terrainState = EffectState("")
        battle.eachEvent("TerrainChange")
        return true
    }

    fun effectiveTerrain(targetIn: Any? = null): String {
        val target = targetIn ?: battle.event?.target
        return if (Js.truthy(battle.runEvent("TryTerrain", target))) terrain else ""
    }

    fun isTerrain(terrain: String, target: Any? = null): Boolean = effectiveTerrain(target) == Js.toID(terrain)

    fun isTerrain(terrains: List<String>, target: Any? = null): Boolean = effectiveTerrain(target) in terrains.map { Js.toID(it) }

    fun getTerrain(): Effect = battle.dex.conditionById(terrain)

    fun addPseudoWeather(statusName: String, sourceIn: Pokemon? = null, sourceEffect: EffectLike? = null): Any? {
        val source = sourceIn ?: battle.event?.target as? Pokemon
        val status = battle.dex.condition(statusName)
        pseudoWeather[status.id]?.let { existing ->
            if (!status.declares("onFieldRestart")) return false
            return battle.singleEvent("FieldRestart", status, existing, this, source, sourceEffect)
        }
        val state = EffectState(status.id).also {
            it.source = source
            it.sourceSlot = source?.getSlot()
            it.duration = status.duration
        }
        pseudoWeather[status.id] = state
        if (status.declares("durationCallback")) {
            requireNotNull(source) { "setting fieldcond without a source" }
            state.duration = Js.int(battle.callback(status, "durationCallback", source, source, sourceEffect))
        }
        if (!Js.truthy(battle.singleEvent("FieldStart", status, state, this, source, sourceEffect))) {
            pseudoWeather.remove(status.id)
            return false
        }
        battle.runEvent("PseudoWeatherChange", source, source, status)
        return true
    }

    fun getPseudoWeather(statusName: String): Effect? {
        val status = battle.dex.condition(statusName)
        return if (pseudoWeather[status.id] != null) status else null
    }

    fun removePseudoWeather(statusName: String): Boolean {
        val status = battle.dex.condition(statusName)
        val state = pseudoWeather[status.id] ?: return false
        battle.singleEvent("FieldEnd", status, state, this)
        pseudoWeather.remove(status.id)
        return true
    }
}
