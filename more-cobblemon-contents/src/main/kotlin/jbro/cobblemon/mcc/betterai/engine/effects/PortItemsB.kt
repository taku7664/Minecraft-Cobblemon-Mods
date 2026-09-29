package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** A second batch of held items with code in `data/items.js` (and Mega Showdown's runtime items). */
object PortItemsB : HookSet() {
    @Suppress("UNCHECKED_CAST")
    private fun boosts(value: Any?): Map<String, Int> = value as Map<String, Int>

    private val MENTAL_HERB_CONDITIONS = listOf("attract", "taunt", "encore", "torment", "disable", "healblock")

    override fun HookRegistrar.define() {
        effect("item:mentalherb/fling") {
            callback("effect") {
                val p = pokemon
                for (first in MENTAL_HERB_CONDITIONS) {
                    if (p.volatiles[first] != null) {
                        for (second in MENTAL_HERB_CONDITIONS) {
                            p.removeVolatile(second)
                            if (first == "attract" && second == "attract") add("-end", p, "move: Attract", "[from] item: Mental Herb")
                        }
                        return@callback Unit
                    }
                }
                Unit
            }
        }
        seed("electricseed", "electricterrain")
        seed("mistyseed", "mistyterrain")
        item("airballoon") {
            on("Start") {
                val t = pokemon
                if (!t.ignoringItem() && field.getPseudoWeather("gravity") == null) add("-item", t, "Air Balloon")
                Unit
            }
            on("DamagingHit") { popBalloon(this.pokemon); Unit }
            on("AfterSubDamage") {
                if (sourceEffect?.effectType == "Move") popBalloon(this.pokemon)
                Unit
            }
        }
        item("widelens") {
            on("SourceModifyAccuracy") { if (Js.isNumber(relay)) chainModify(intArrayOf(4505, 4096)) else Unit }
        }
        item("brightpowder") {
            on("ModifyAccuracy") { if (!Js.isNumber(relay)) Unit else chainModify(intArrayOf(3686, 4096)) }
        }
        item("ejectbutton") {
            on("AfterMoveSecondary") {
                val t = pokemon
                val src = sourceMon
                val m = activeMove
                if (src != null && src !== t && t.hp != 0 && m != null && m.category != "Status" && !m.flag("futuremove")) {
                    if (battle.canSwitch(t.side) == 0 || t.forceSwitchFlag || t.beingCalledBack || t.isSkyDropped()) return@on Unit
                    if (t.volatiles["commanding"] != null || t.volatiles["commanded"] != null) return@on Unit
                    for (p in battle.getAllActive()) if (p.switchFlag == true) return@on Unit
                    t.switchFlag = true
                    if (t.useItem()) src.switchFlag = false else t.switchFlag = false
                }
                Unit
            }
        }
        item("toxicorb") {
            on("Residual") { pokemon.trySetStatus("tox", pokemon); Unit }
        }
        item("ejectpack") {
            on("AfterBoost") {
                if (battle.activeMove?.id == "partingshot") return@on Unit
                val t = pokemon
                var eject = false
                for ((_, v) in boosts(relay)) if (v < 0) eject = true
                if (eject) {
                    if (t.hp != 0) {
                        if (battle.canSwitch(t.side) == 0) return@on Unit
                        if (t.volatiles["commanding"] != null || t.volatiles["commanded"] != null) return@on Unit
                        for (p in battle.getAllActive()) if (p.switchFlag == true) return@on Unit
                        if (t.useItem()) t.switchFlag = true
                    }
                }
                Unit
            }
        }
        pinchBerry("salacberry") { boost(linkedMapOf("spe" to 1)) }
        pinchBerry("lansatberry") { pokemon.addVolatile("focusenergy") }
        pinchBerry("starfberry") {
            val p = pokemon
            val stats = ArrayList<String>()
            for ((stat, v) in p.boosts) if (stat != "accuracy" && stat != "evasion" && v < 6) stats.add(stat)
            if (stats.isNotEmpty()) {
                val randomStat = battle.sample(stats)
                boost(linkedMapOf(randomStat to 2))
            }
        }
        item("iapapaberry") {
            on("Update") { if (pinchReady(pokemon)) pokemon.eatItem(); Unit }
            on("TryEatItem") {
                if (!Js.truthy(battle.runEvent("TryHeal", pokemon, null, self, pokemon.baseMaxhp / 3.0))) false else Unit
            }
            on("Eat") {
                heal(pokemon.baseMaxhp / 3.0)
                if (pokemon.getNature()?.minus == "def") pokemon.addVolatile("confusion")
                Unit
            }
        }
        item("oranberry") {
            on("Update") { if (pokemon.hp <= pokemon.maxhp / 2.0) pokemon.eatItem(); Unit }
            on("TryEatItem") { if (!Js.truthy(battle.runEvent("TryHeal", pokemon, null, self, 10))) false else Unit }
            on("Eat") { heal(10); Unit }
        }
        item("persimberry") {
            on("Update") { if (pokemon.volatiles["confusion"] != null) pokemon.eatItem(); Unit }
            on("Eat") { pokemon.removeVolatile("confusion"); Unit }
        }
        item("marangaberry") {
            on("AfterMoveSecondary") { if (activeMove?.category == "Special") pokemon.eatItem(); Unit }
            on("Eat") { boost(linkedMapOf("spd" to 1)); Unit }
        }
        item("micleberry") {
            on("Residual") { if (pinchReady(pokemon)) pokemon.eatItem(); Unit }
            on("Eat") { pokemon.addVolatile("micleberry"); Unit }
            condition {
                on("SourceAccuracy") {
                    val src = sourceMon!!
                    if (!Js.truthy(move.ohko)) {
                        add("-enditem", src, "Micle Berry")
                        src.removeVolatile("micleberry")
                        if (Js.isNumber(relay)) return@on chainModify(intArrayOf(4915, 4096))
                    }
                    Unit
                }
            }
        }
        item("quickclaw") {
            on("FractionalPriority") {
                val p = pokemon
                if (move.category == "Status" && p.hasAbility("myceliummight")) return@on Unit
                if (relayNum <= 0 && randomChance(1, 5)) {
                    add("-activate", p, "item: Quick Claw")
                    return@on 0.1
                }
                Unit
            }
        }
        item("roomservice") {
            on("Start") {
                val p = pokemon
                if (!p.ignoringItem() && field.getPseudoWeather("trickroom") != null) p.useItem()
                Unit
            }
            on("AnyPseudoWeatherChange") {
                val p = state.target as Pokemon
                if (field.getPseudoWeather("trickroom") != null) p.useItem(p)
                Unit
            }
        }
        item("normalgem") {
            on("SourceTryPrimaryHit") {
                val t = targetMon
                val src = sourceMon!!
                val m = move
                if (t === src || m.category == "Status" || m.flag("pledgecombo")) return@on Unit
                if (m.type == "Normal" && src.useItem()) src.addVolatile("gem")
                Unit
            }
        }
        item("kingsrock") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.category != "Status") {
                    val list = m.secondaries ?: ArrayList<HitData>().also { m.secondaries = it }
                    for (secondary in list) if (secondary.hitVolatileStatus == "flinch") return@on Unit
                    list.add(LiteralHit(chance = 10, hitVolatileStatus = "flinch"))
                }
                Unit
            }
        }
        item("redcard") {
            on("AfterMoveSecondary") {
                val t = pokemon
                val src = sourceMon
                val m = activeMove
                if (src != null && src !== t && src.hp != 0 && t.hp != 0 && m != null && m.category != "Status") {
                    if (!src.isActive || battle.canSwitch(src.side) == 0 || src.forceSwitchFlag || t.forceSwitchFlag) return@on Unit
                    if (t.useItem(src)) {
                        if (Js.truthy(battle.runEvent("DragOut", src, t, m))) src.forceSwitchFlag = true
                    }
                }
                Unit
            }
        }
        plate("zapplate", "Electric")
        plate("spookyplate", "Ghost")
        plate("earthplate", "Ground")
        plate("fistplate", "Fighting")
        plate("skyplate", "Flying")
        plate("stoneplate", "Rock")
        for ((id, type) in listOf(
            "mysticwater" to "Water", "blackglasses" to "Dark", "dragonfang" to "Dragon", "magnet" to "Electric",
            "metalcoat" to "Steel", "poisonbarb" to "Poison", "spelltag" to "Ghost", "hardstone" to "Rock",
            "silverpowder" to "Bug", "twistedspoon" to "Psychic", "softsand" to "Ground",
        )) item(id) { on("BasePower") { if (move.type == type) chainModify(intArrayOf(4915, 4096)) else Unit } }
        originItem("adamantcrystal", 483, "Steel", takeLock = true)
        originItem("griseouscore", 487, "Ghost", takeLock = true)
        originItem("lustrousglobe", 484, "Water", takeLock = true)
        originItem("lustrousorb", 484, "Water", takeLock = false)
        originItem("griseousorb", 487, "Ghost", takeLock = false)
        item("souldew") {
            on("BasePower") {
                val num = pokemon.baseSpecies.num
                if ((num == 380 || num == 381) && (move.type == "Psychic" || move.type == "Dragon")) chainModify(intArrayOf(4915, 4096)) else Unit
            }
        }
        item("muscleband") {
            on("BasePower") { if (move.category == "Physical") chainModify(intArrayOf(4505, 4096)) else Unit }
        }
        item("rustedsword") {
            on("SwitchIn") {
                val p = pokemon
                if (p.isActive && p.baseSpecies.name == "Zacian") {
                    p.formeChange("Zacian-Crowned")
                    for (slot in p.moveSlots) {
                        if (slot.id != "ironhead") continue
                        val oldMove = dex.move(slot.id)!!
                        val newMove = dex.move("behemothblade") ?: continue
                        val ppRatio = if (oldMove.pp != 0) slot.maxpp.toDouble() / oldMove.pp else 1.0
                        val newMaxPP = Math.floor(newMove.pp * ppRatio).toInt()
                        slot.id = newMove.id
                        slot.pp = newMaxPP
                        slot.maxpp = newMaxPP
                    }
                }
                Unit
            }
            on("TakeItem") { speciesLock(this, 888) }
        }
        item("ironball") {
            on("Effectiveness") {
                val t = targetMon ?: return@on Unit
                if (t.volatiles["ingrain"] != null || t.volatiles["smackdown"] != null || field.getPseudoWeather("gravity") != null) return@on Unit
                if (move.type == "Ground" && t.hasType("Flying")) 0 else Unit
            }
            on("ModifySpe") { chainModify(0.5) }
        }
        item("lightball") {
            on("ModifyAtk") { if (pokemon.baseSpecies.baseSpecies == "Pikachu") chainModify(2) else Unit }
            on("ModifySpA") { if (pokemon.baseSpecies.baseSpecies == "Pikachu") chainModify(2) else Unit }
        }
        for ((id, type) in listOf(
            "yacheberry" to "Ice", "shucaberry" to "Ground", "rindoberry" to "Grass", "colburberry" to "Dark",
            "occaberry" to "Fire", "wacanberry" to "Electric", "kasibberry" to "Ghost", "babiriberry" to "Steel",
            "tangaberry" to "Bug",
        )) resistBerry(id, type)
        item("chilanberry") {
            on("SourceModifyDamage") {
                val t = sourceMon!!
                val m = move
                if (m.type == "Normal" && (t.volatiles["substitute"] == null || m.flag("bypasssub") || m.infiltrates)) {
                    if (t.eatItem()) {
                        add("-enditem", t, self, "[weaken]")
                        return@on chainModify(0.5)
                    }
                }
                Unit
            }
            on("Eat") { Unit }
        }
        item("abilityshield") {
            on("SetAbility") {
                val e = sourceEffect
                if (e != null && e.effectType == "Ability" && e.name != "Trace") add("-ability", source, e)
                add("-block", target, "item: Ability Shield")
                null
            }
        }
        item("adrenalineorb") {
            on("AfterBoost") {
                val t = pokemon
                if (t.boosts["spe"] == 6 || boosts(relay)["atk"] == 0) return@on Unit
                if (sourceEffect?.name == "Intimidate") t.useItem()
                Unit
            }
        }
        item("mirrorherb") {
            on("FoeAfterBoost") {
                val e = sourceEffect
                if (e?.name == "Opportunist" || e?.name == "Mirror Herb") return@on Unit
                val boostPlus = LinkedHashMap<String, Int>()
                var statsRaised = false
                for ((k, v) in boosts(relay)) {
                    if (v > 0) {
                        boostPlus[k] = v
                        statsRaised = true
                    }
                }
                if (!statsRaised) return@on Unit
                val p = state.target as Pokemon
                p.useItem()
                boost(boostPlus, p)
                Unit
            }
        }
        boostOnHit("absorbbulb", "Water")
        boostOnHit("snowball", "Ice")
        item("focusband") {
            on("Damage") {
                val t = pokemon
                val e = sourceEffect
                if (randomChance(1, 10) && relayNum >= t.hp && e != null && e.effectType == "Move") {
                    add("-activate", t, "item: Focus Band")
                    return@on t.hp - 1
                }
                Unit
            }
        }
        for (id in listOf("powerbracer", "powerweight", "powerband")) item(id) { on("ModifySpe") { chainModify(0.5) } }
        item("stickybarb") {
            on("Residual") { damage(pokemon.baseMaxhp / 8.0); Unit }
            on("Hit") {
                val t = pokemon
                val src = sourceMon
                val m = activeMove
                if (src != null && src !== t && src.item.isEmpty() && m != null && battle.checkMoveMakesContact(m, src, t)) {
                    val barb = t.takeItem()
                    if (!Js.truthy(barb)) return@on Unit
                    src.setItem((barb as EffectLike).id)
                }
                Unit
            }
        }
        item("utilityumbrella") {
            on("Start") {
                val p = pokemon
                if (!p.ignoringItem()) return@on Unit
                if (field.effectiveWeather() in SUN_RAIN) battle.runEvent("WeatherChange", p, p, self)
                Unit
            }
            on("Update") {
                val p = pokemon
                if (!Js.truthy(state["inactive"])) return@on Unit
                state["inactive"] = false
                if (field.effectiveWeather() in SUN_RAIN) battle.runEvent("WeatherChange", p, p, self)
                Unit
            }
            on("End") {
                val p = pokemon
                if (field.effectiveWeather() in SUN_RAIN) battle.runEvent("WeatherChange", p, p, self)
                state["inactive"] = true
                Unit
            }
        }
        item("destinyknot") {
            on("Attract") {
                val t = targetMon
                val src = sourceMon
                if (src == null || src === t) return@on Unit
                if (src.volatiles["attract"] == null) src.addVolatile("attract", t)
                Unit
            }
        }
        item("floatstone") {
            on("ModifyWeight") { Js.trunc(relayNum / 2) }
        }
        for (id in MEGA_STONES) item(id) {
            on("TakeItem") {
                val item = relay as EffectLike
                @Suppress("UNCHECKED_CAST")
                val stones = item.data("megaStone") as? Map<String, Any?>
                val evolves = Js.truthy(stones?.get(pokemon.baseSpecies.baseSpecies))
                !evolves
            }
        }
        item("legendplate") {
            on("TryMove") {
                val p = pokemon
                val t = sourceMon!!
                val m = move
                if (!(p.hasItem("legendplate") && m.id == "judgment")) return@on Unit
                val tt = if (t.getTypes().isNotEmpty()) t.getTypes() else t.species.types
                var bt = ArrayList<String>()
                var hs = Double.NEGATIVE_INFINITY
                for (at in dex.typeNames) {
                    if (tt.any { dt -> dex.immune(at, dt) }) continue
                    val s = tt.sumOf { dt ->
                        when (dex.effectiveness(at, dt)) {
                            2 -> 4
                            1 -> 2
                            -1 -> -1
                            -2 -> -2
                            else -> 0
                        }.toInt()
                    }.toDouble()
                    if (s > hs) {
                        bt = arrayListOf(at)
                        hs = s
                    } else if (s == hs) bt.add(at)
                }
                val btFinal = if ("Normal" in bt) "Normal" else battle.sample(bt)
                if (p.name != "Arceus") return@on Unit
                val f = "Arceus-$btFinal"
                val sp = dex.species("arceus${btFinal.lowercase()}")
                if (sp != null && dex.species(f) != null && p.species.name != f) p.formeChange(f, null, true)
                m.type = btFinal
                m.ignoreAbility = true
                Unit
            }
        }
    }

    private val SUN_RAIN = listOf("sunnyday", "raindance", "desolateland", "primordialsea")

    private val MEGA_STONES = listOf(
        "abomasite", "aerodactylite", "alakazite", "ampharosite", "audinite", "beedrillite", "blazikenite", "charizarditex",
        "diancite", "garchompite", "gengarite", "gyaradosite", "houndoominite", "latiasite", "lucarionite", "mawilite",
        "metagrossite", "mewtwonitey", "pidgeotite", "sablenite", "sceptilite", "sharpedonite", "steelixite", "tyranitarite",
    )

    private fun popBalloon(target: Pokemon) {
        val battle = target.battle
        battle.add("-enditem", target, "Air Balloon")
        target.item = ""
        target.itemState = EffectState("").also { it.target = target }
        battle.runEvent("AfterUseItem", target, null, null, battle.dex.item("airballoon"))
    }

    /** The pinch condition of the quarter-HP berries, with Gluttony's half-HP threshold. */
    fun pinchReady(p: Pokemon): Boolean =
        p.hp <= p.maxhp / 4.0 || (p.hp <= p.maxhp / 2.0 && p.hasAbility("gluttony") && Js.truthy(p.abilityState["gluttony"]))

    private fun HookRegistrar.pinchBerry(id: String, eat: HookCall.() -> Any?) = item(id) {
        on("Update") { if (pinchReady(pokemon)) pokemon.eatItem(); Unit }
        on("Eat") { eat(); Unit }
    }

    private fun HookRegistrar.seed(id: String, terrain: String) = item(id) {
        on("Start") {
            val p = pokemon
            if (!p.ignoringItem() && field.isTerrain(terrain)) p.useItem()
            Unit
        }
        on("TerrainChange") {
            if (field.isTerrain(terrain)) pokemon.useItem()
            Unit
        }
    }

    private fun speciesLock(call: HookCall, num: Int): Any? = with(call) {
        val src = sourceMon
        if ((src != null && src.baseSpecies.num == num) || pokemon.baseSpecies.num == num) false else true
    }

    private fun HookRegistrar.plate(id: String, type: String) = item(id) {
        on("BasePower") { if (move.type == type) chainModify(intArrayOf(4915, 4096)) else Unit }
        on("TakeItem") { speciesLock(this, 493) }
    }

    /** Adamant/Lustrous/Griseous items: a boost for one species' STAB types, and for the Origin items a take lock. */
    private fun HookRegistrar.originItem(id: String, num: Int, type: String, takeLock: Boolean) = item(id) {
        on("BasePower") {
            if (pokemon.baseSpecies.num == num && (move.type == type || move.type == "Dragon")) chainModify(intArrayOf(4915, 4096)) else Unit
        }
        if (takeLock) on("TakeItem") { speciesLock(this, num) }
    }

    private fun HookRegistrar.resistBerry(id: String, type: String) = item(id) {
        on("SourceModifyDamage") {
            val t = sourceMon!!
            val m = move
            if (m.type == type && t.getMoveHitData(m).typeMod > 0) {
                val hitSub = t.volatiles["substitute"] != null && !m.flag("bypasssub") && !m.infiltrates
                if (hitSub) return@on Unit
                if (t.eatItem()) {
                    add("-enditem", t, self, "[weaken]")
                    return@on chainModify(0.5)
                }
            }
            Unit
        }
        on("Eat") { Unit }
    }

    private fun HookRegistrar.boostOnHit(id: String, type: String) = item(id) {
        on("DamagingHit") { if (move.type == type) pokemon.useItem(); Unit }
    }
}
