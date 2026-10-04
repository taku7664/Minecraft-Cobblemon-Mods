package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** The most used abilities with code in `data/abilities.js`, ported first. */
object TopAbilities : HookSet() {
    @Suppress("UNCHECKED_CAST")
    private fun boosts(value: Any?): MutableMap<String, Int> = value as MutableMap<String, Int>

    override fun HookRegistrar.define() {
        ability("intimidate") {
            on("Start") {
                val p = pokemon
                var activated = false
                for (t in p.adjacentFoes()) {
                    if (!activated) {
                        add("-ability", p, "Intimidate", "boost")
                        activated = true
                    }
                    if (t.volatiles["substitute"] != null) add("-immune", t)
                    else battle.boost(mapOf("atk" to -1), t, p, null, true)
                }
                Unit
            }
        }
        ability("pressure") {
            on("Start") { add("-ability", pokemon, "Pressure"); Unit }
            on("DeductPP") { if (pokemon.isAlly(sourceMon)) Unit else 1 }
        }
        ability("prankster") {
            on("ModifyPriority") {
                val m = activeMove
                if (m?.category == "Status") {
                    m.pranksterBoosted = true
                    Js.number(relayNum + 1)
                } else Unit
            }
        }
        weatherSpeed("chlorophyll", listOf("sunnyday", "desolateland"))
        weatherSpeed("swiftswim", listOf("raindance", "primordialsea"))
        paradox("protosynthesis", "Protosynthesis", "WeatherChange") { it.battle.field.isWeather("sunnyday") }
        paradox("quarkdrive", "Quark Drive", "TerrainChange") { it.battle.field.isTerrain("electricterrain") }
        ability("sturdy") {
            on("TryHit") {
                if (Js.truthy(move.ohko)) {
                    add("-immune", pokemon, "[from] ability: Sturdy")
                    null
                } else Unit
            }
            on("Damage") {
                val t = pokemon
                val e = sourceEffect
                if (t.hp == t.maxhp && relayNum >= t.hp && e != null && e.effectType == "Move") {
                    add("-ability", t, "Sturdy")
                    t.hp - 1
                } else Unit
            }
        }
        ability("regenerator") {
            on("SwitchOut") { pokemon.heal(pokemon.baseMaxhp / 3.0); Unit }
        }
        absorb("waterabsorb", "Water", "Water Absorb")
        absorb("voltabsorb", "Electric", "Volt Absorb")
        ability("innerfocus") {
            on("TryAddVolatile") { if ((relay as EffectLike).id == "flinch") null else Unit }
            on("TryBoost") {
                val b = boosts(relay)
                if (sourceEffect?.name == "Intimidate" && (b["atk"] ?: 0) != 0) {
                    b.remove("atk")
                    add("-fail", target, "unboost", "Attack", "[from] ability: Inner Focus", "[of] $target")
                }
                Unit
            }
        }
        ability("frisk") {
            on("Start") {
                val p = pokemon
                for (t in p.foes()) {
                    if (t.item.isNotEmpty()) add("-item", t, t.getItem().name, "[from] ability: Frisk", "[of] $p", "[identify]")
                }
                Unit
            }
        }
        ability("technician") {
            on("BasePower") {
                val after = battle.modify(relayInt, battle.event!!.modifier)
                if (after <= 60) chainModify(1.5) else Unit
            }
        }
        ability("flashfire") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.type == "Fire") {
                    move.accuracy = true
                    if (!Js.truthy(t.addVolatile("flashfire"))) add("-immune", t, "[from] ability: Flash Fire")
                    null
                } else Unit
            }
            on("End") { pokemon.removeVolatile("flashfire"); Unit }
            condition {
                on("Start") { add("-start", target, "ability: Flash Fire"); Unit }
                on("ModifyAtk") { if (move.type == "Fire" && pokemon.hasAbility("flashfire")) chainModify(1.5) else Unit }
                on("ModifySpA") { if (move.type == "Fire" && pokemon.hasAbility("flashfire")) chainModify(1.5) else Unit }
                on("End") { add("-end", target, "ability: Flash Fire", "[silent]"); Unit }
            }
        }
        ability("clearbody") {
            on("TryBoost") { clearBodyTryBoost(this, "[from] ability: Clear Body") }
        }
        ability("defiant") {
            on("AfterEachBoost") { afterLoweredBoost(this, "atk") }
        }
        ability("competitive") {
            on("AfterEachBoost") { afterLoweredBoost(this, "spa") }
        }
        ability("unburden") {
            on("AfterUseItem") {
                if (pokemon !== state.target) return@on Unit
                pokemon.addVolatile("unburden")
                Unit
            }
            on("TakeItem") { pokemon.addVolatile("unburden"); Unit }
            on("End") { pokemon.removeVolatile("unburden"); Unit }
            condition {
                on("ModifySpe") { if (pokemon.item.isEmpty() && !pokemon.ignoringAbility()) chainModify(2) else Unit }
            }
        }
        ability("naturalcure") {
            on("CheckShow") {
                val p = pokemon
                if (p.side.active.size == 1) return@on Unit
                if (p.showCure != null) return@on Unit
                val cureList = ArrayList<Pokemon>()
                var noCureCount = 0
                for (cur in p.side.active) {
                    if (cur == null || cur.status.isEmpty()) continue
                    if (cur.showCure == true) continue
                    val species = cur.species
                    if ("Natural Cure" !in species.abilities.values) continue
                    if (species.abilities["1"] == null && species.abilities["H"] == null) continue
                    if (cur !== p && battle.queue.willSwitch(cur) == null) continue
                    if (cur.hasAbility("naturalcure")) cureList.add(cur) else noCureCount++
                }
                if (cureList.isEmpty() || noCureCount == 0) {
                    for (mon in cureList) mon.showCure = true
                } else {
                    add("-message", "(${cureList.size} of ${p.side.name}'s pokemon ${if (cureList.size == 1) "was" else "were"} cured by Natural Cure.)")
                    for (mon in cureList) mon.showCure = false
                }
                Unit
            }
            on("SwitchOut") {
                val p = pokemon
                if (p.status.isEmpty()) return@on Unit
                if (p.showCure == null) p.showCure = true
                if (p.showCure == true) add("-curestatus", p, p.status, "[from] ability: Natural Cure")
                p.clearStatus()
                if (p.showCure != true) p.showCure = null
                Unit
            }
        }
    }

    private fun HookRegistrar.weatherSpeed(id: String, weathers: List<String>) = ability(id) {
        on("ModifySpe") { if (pokemon.effectiveWeather() in weathers) chainModify(2) else Unit }
    }

    private fun HookRegistrar.absorb(id: String, type: String, name: String) = ability(id) {
        on("TryHit") {
            val t = pokemon
            if (t !== source && move.type == type) {
                if (!Js.truthy(battle.heal(t.baseMaxhp / 4.0))) add("-immune", t, "[from] ability: $name")
                null
            } else Unit
        }
    }

    /** Protosynthesis and Quark Drive: the same boost, keyed on sun or Electric Terrain. */
    private fun HookRegistrar.paradox(id: String, name: String, changeEvent: String, active: (jbro.cobblemon.mcc.betterai.engine.hooks.HookCall) -> Boolean) =
        ability(id) {
            on("Start") { battle.singleEvent(changeEvent, self, state, pokemon); Unit }
            on(changeEvent) {
                val p = pokemon
                if (active(this)) p.addVolatile(id)
                else if (!Js.truthy(p.volatiles[id]?.get("fromBooster"))) p.removeVolatile(id)
                Unit
            }
            on("End") {
                pokemon.volatiles.remove(id)
                add("-end", pokemon, name, "[silent]")
                Unit
            }
            condition {
                on("Start") {
                    val p = pokemon
                    if (sourceEffect?.name == "Booster Energy") {
                        state["fromBooster"] = true
                        add("-activate", p, "ability: $name", "[fromitem]")
                    } else {
                        add("-activate", p, "ability: $name")
                    }
                    state["bestStat"] = p.getBestStat(false, true)
                    add("-start", p, id + state["bestStat"])
                    Unit
                }
                paradoxStat(this, "Atk", "atk", intArrayOf(5325, 4096))
                paradoxStat(this, "Def", "def", intArrayOf(5325, 4096))
                paradoxStat(this, "SpA", "spa", intArrayOf(5325, 4096))
                paradoxStat(this, "SpD", "spd", intArrayOf(5325, 4096))
                on("ModifySpe") { if (state["bestStat"] != "spe" || pokemon.ignoringAbility()) Unit else chainModify(1.5) }
                on("End") { add("-end", pokemon, name); Unit }
            }
        }

    private fun paradoxStat(hooks: EffectHooks, event: String, stat: String, modifier: IntArray) = hooks.on("Modify$event") {
        if (state["bestStat"] != stat || pokemon.ignoringAbility()) Unit else chainModify(modifier)
    }

    fun clearBodyTryBoost(call: jbro.cobblemon.mcc.betterai.engine.hooks.HookCall, from: String): Any? = with(call) {
        if (source != null && target === source) return@with Unit
        val b = boosts(relay)
        var showMsg = false
        for (key in b.keys.toList()) {
            if ((b[key] ?: 0) < 0) {
                b.remove(key)
                showMsg = true
            }
        }
        val e = sourceEffect
        val hasSecondaries = (e as? ActiveMove)?.secondaries != null || (e != null && e !is ActiveMove && e.data("secondaries") != null)
        if (showMsg && !hasSecondaries && e?.id != "octolock") add("-fail", target, "unboost", from, "[of] $target")
        Unit
    }

    fun afterLoweredBoost(call: jbro.cobblemon.mcc.betterai.engine.hooks.HookCall, stat: String): Any? = with(call) {
        val src = sourceMon
        val t = pokemon
        if (src == null || t.isAlly(src)) return@with Unit
        if (boosts(relay).values.any { it < 0 }) battle.boost(mapOf(stat to 2), t, t, null, false, true)
        Unit
    }
}
