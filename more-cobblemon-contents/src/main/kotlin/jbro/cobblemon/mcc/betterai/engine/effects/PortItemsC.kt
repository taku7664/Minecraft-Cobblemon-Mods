package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** Held items with code in `data/items.js` (and Mega Showdown's replacements), the third porting batch. */
object PortItemsC : HookSet() {
    private val MEGA_STONES = listOf("absolite", "aggronite", "altarianite", "banettite", "blastoisinite", "cameruptite",
        "charizarditey", "galladite", "gardevoirite", "glalitite", "heracronite", "kangaskhanite", "latiosite", "lopunnite",
        "manectite", "medichamite", "mewtwonitex", "pinsirite", "salamencite", "scizorite", "slowbronite", "swampertite",
        "venusaurite")

    /** `pokemon.hp <= pokemon.maxhp / 4 || pokemon.hp <= pokemon.maxhp / 2 && gluttony` */
    private fun pinchHp(p: Pokemon): Boolean =
        p.hp <= p.maxhp / 4.0 || (p.hp <= p.maxhp / 2.0 && p.hasAbility("gluttony") && Js.truthy(p.abilityState["gluttony"]))

    override fun HookRegistrar.define() {
        item("custapberry") {
            on("FractionalPriority") {
                val p = pokemon
                if (relayNum <= 0 && pinchHp(p)) {
                    if (p.eatItem()) {
                        add("-activate", p, "item: Custap Berry", "[consumed]")
                        return@on 0.1
                    }
                }
                Unit
            }
            on("Eat") { Unit }
        }
        item("flameorb") {
            on("Residual") { pokemon.trySetStatus("brn", pokemon); Unit }
        }
        item("powerherb") {
            on("ChargeMove") {
                val p = pokemon
                if (p.useItem()) {
                    battle.attrLastMove("[still]")
                    battle.addMove("-anim", p, move.name, source)
                    false
                } else Unit
            }
        }
        item("blacksludge") {
            on("Residual") {
                val p = pokemon
                if (p.hasType("Poison")) heal(p.baseMaxhp / 16.0) else damage(p.baseMaxhp / 8.0)
                Unit
            }
        }
        item("throatspray") {
            on("AfterMoveSecondarySelf") {
                if (move.flag("sound")) pokemon.useItem()
                Unit
            }
        }
        item("lumberry") {
            on("AfterSetStatus") { pokemon.eatItem(); Unit }
            on("Update") {
                val p = pokemon
                if (p.status.isNotEmpty() || p.volatiles["confusion"] != null) p.eatItem()
                Unit
            }
            on("Eat") {
                pokemon.cureStatus()
                pokemon.removeVolatile("confusion")
                Unit
            }
        }
        item("whiteherb") {
            on("Update") {
                val p = pokemon
                var activate = false
                val boosts = LinkedHashMap<String, Int>()
                for ((i, v) in p.boosts) {
                    if (v < 0) {
                        activate = true
                        boosts[i] = 0
                    }
                }
                if (activate && p.useItem()) {
                    p.setBoost(boosts)
                    add("-clearnegativeboost", p, "[silent]")
                }
                Unit
            }
        }
        effect("item:whiteherb/fling") {
            callback("effect") {
                val p = pokemon
                var activate = false
                val boosts = LinkedHashMap<String, Int>()
                for ((i, v) in p.boosts) {
                    if (v < 0) {
                        activate = true
                        boosts[i] = 0
                    }
                }
                if (activate) {
                    p.setBoost(boosts)
                    add("-clearnegativeboost", p, "[silent]")
                }
                Unit
            }
        }
        item("expertbelt") {
            on("ModifyDamage") {
                val m = activeMove
                if (m != null && sourceMon!!.getMoveHitData(m).typeMod > 0) chainModify(intArrayOf(4915, 4096)) else Unit
            }
        }
        plate("pixieplate", "Fairy")
        plate("meadowplate", "Grass")
        plate("dreadplate", "Dark")
        plate("splashplate", "Water")
        plate("insectplate", "Bug")
        plate("flameplate", "Fire")
        plate("ironplate", "Steel")
        plate("mindplate", "Psychic")
        plate("toxicplate", "Poison")
        plate("icicleplate", "Ice")
        plate("dracoplate", "Dragon")
        item("keeberry") {
            on("AfterMoveSecondary") {
                val m = move
                if (m.category == "Physical") {
                    if (m.id == "present" && m.heal != null) return@on Unit
                    pokemon.eatItem()
                }
                Unit
            }
            on("Eat") { boost(mapOf("def" to 1)); Unit }
        }
        critItem("scopelens")
        critItem("razorclaw")
        seed("psychicseed", "psychicterrain")
        seed("grassyseed", "grassyterrain")
        mask("cornerstonemask", "Ogerpon-Cornerstone")
        mask("hearthflamemask", "Ogerpon-Hearthflame")
        mask("wellspringmask", "Ogerpon-Wellspring")
        item("rustedshield") {
            on("SwitchIn") {
                val p = pokemon
                if (p.isActive && p.baseSpecies.name == "Zamazenta") {
                    p.formeChange("Zamazenta-Crowned")
                    val size = p.moveSlots.size
                    for (i in 0 until size) {
                        val moveSlot = p.moveSlots[i]
                        if (moveSlot.id != "ironhead") continue
                        val oldMove = dex.move(moveSlot.id)!!
                        val newMove = dex.move("behemothbash") ?: continue
                        val ppRatio = if (oldMove.pp != 0) moveSlot.maxpp.toDouble() / oldMove.pp else 1.0
                        val newMaxPP = Math.floor(newMove.pp * ppRatio).toInt()
                        moveSlot.id = newMove.id
                        moveSlot.pp = newMaxPP
                        moveSlot.maxpp = newMaxPP
                    }
                }
                Unit
            }
            on("TakeItem") {
                val src = sourceMon
                if ((src != null && src.baseSpecies.num == 889) || pokemon.baseSpecies.num == 889) false else true
            }
        }
        typeBooster("silkscarf", "Normal")
        typeBooster("sharpbeak", "Flying")
        typeBooster("charcoal", "Fire")
        typeBooster("miracleseed", "Grass")
        typeBooster("nevermeltice", "Ice")
        typeBooster("fairyfeather", "Fairy")
        typeBooster("blackbelt", "Fighting")
        confuseBerry("aguavberry", "spd")
        confuseBerry("figyberry", "atk")
        confuseBerry("wikiberry", "spa")
        confuseBerry("magoberry", "spe")
        item("punchingglove") {
            on("BasePower") { if (move.flag("punch")) chainModify(intArrayOf(4506, 4096)) else Unit }
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.flag("punch")) m.flags.remove("contact")
                Unit
            }
        }
        item("shellbell") {
            on("AfterMoveSecondarySelf") {
                val p = pokemon
                val m = move
                if (m.totalDamage != 0 && !p.forceSwitchFlag) heal(m.totalDamage / 8.0, p)
                Unit
            }
        }
        item("leppaberry") {
            on("Update") {
                val p = pokemon
                if (p.hp == 0) return@on Unit
                if (p.moveSlots.any { it.pp == 0 }) p.eatItem()
                Unit
            }
            on("Eat") {
                val p = pokemon
                val moveSlot = p.moveSlots.firstOrNull { it.pp == 0 } ?: p.moveSlots.firstOrNull { it.pp < it.maxpp } ?: return@on Unit
                moveSlot.pp += 10
                if (moveSlot.pp > moveSlot.maxpp) moveSlot.pp = moveSlot.maxpp
                add("-activate", p, "item: Leppa Berry", moveSlot.move, "[consumed]")
                Unit
            }
        }
        statusBerry("chestoberry", listOf("slp"))
        statusBerry("rawstberry", listOf("brn"))
        statusBerry("cheriberry", listOf("par"))
        statusBerry("aspearberry", listOf("frz"))
        statusBerry("pechaberry", listOf("psn", "tox"))
        item("zoomlens") {
            on("SourceModifyAccuracy") {
                if (Js.isNumber(relay) && battle.queue.willMove(pokemon) == null) chainModify(intArrayOf(4915, 4096)) else Unit
            }
        }
        item("bigroot") {
            on("TryHeal") {
                val heals = listOf("drain", "leechseed", "ingrain", "aquaring", "strengthsap")
                if (sourceEffect?.id in heals) chainModify(intArrayOf(5324, 4096)) else Unit
            }
        }
        resistBerry("chopleberry", "Fighting")
        resistBerry("habanberry", "Dragon")
        resistBerry("roseliberry", "Fairy")
        resistBerry("cobaberry", "Flying")
        resistBerry("payapaberry", "Psychic")
        resistBerry("passhoberry", "Water")
        resistBerry("chartiberry", "Rock")
        resistBerry("kebiaberry", "Poison")
        item("metronome") {
            on("Start") { pokemon.addVolatile("metronome"); Unit }
            condition {
                on("Start") {
                    state["lastMove"] = ""
                    state["numConsecutive"] = 0
                    Unit
                }
                on("TryMove") {
                    val p = pokemon
                    val m = move
                    if (!p.hasItem("metronome")) {
                        p.removeVolatile("metronome")
                        return@on Unit
                    }
                    if (state["lastMove"] == m.id && Js.truthy(p.moveLastTurnResult)) {
                        state["numConsecutive"] = state.int("numConsecutive") + 1
                    } else if (p.volatiles["twoturnmove"] != null) {
                        if (state["lastMove"] != m.id) state["numConsecutive"] = 1
                        else state["numConsecutive"] = state.int("numConsecutive") + 1
                    } else {
                        state["numConsecutive"] = 0
                    }
                    state["lastMove"] = m.id
                    Unit
                }
                on("ModifyDamage") {
                    val dmgMod = intArrayOf(4096, 4915, 5734, 6553, 7372, 8192)
                    val numConsecutive = if (state.int("numConsecutive") > 5) 5 else state.int("numConsecutive")
                    chainModify(intArrayOf(dmgMod[numConsecutive], 4096))
                }
            }
        }
        item("wiseglasses") {
            on("BasePower") { if (move.category == "Special") chainModify(intArrayOf(4505, 4096)) else Unit }
        }
        item("adamantorb") {
            on("BasePower") {
                if (pokemon.baseSpecies.num == 483 && (move.type == "Steel" || move.type == "Dragon")) chainModify(intArrayOf(4915, 4096)) else Unit
            }
        }
        halfSpeed("powerlens")
        halfSpeed("poweranklet")
        halfSpeed("powerbelt")
        pinchBerry("liechiberry", "atk")
        pinchBerry("petayaberry", "spa")
        pinchBerry("ganlonberry", "def")
        pinchBerry("apicotberry", "spd")
        retaliateBerry("jabocaberry", "Physical")
        retaliateBerry("rowapberry", "Special")
        item("shedshell") {
            on("TrapPokemon") {
                pokemon.maybeTrapped = false
                pokemon.trapped = false
                Unit
            }
        }
        item("cellbattery") {
            on("DamagingHit") {
                if (move.type == "Electric") pokemon.useItem()
                Unit
            }
        }
        item("luminousmoss") {
            on("DamagingHit") {
                if (move.type == "Water") pokemon.useItem()
                Unit
            }
        }
        item("razorfang") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.category != "Status") {
                    val list = m.secondaries ?: ArrayList<HitData>().also { m.secondaries = it }
                    for (secondary in list) {
                        if (secondary.hitVolatileStatus == "flinch") return@on Unit
                    }
                    list.add(LiteralHit(chance = 10, hitVolatileStatus = "flinch"))
                }
                Unit
            }
        }
        item("enigmaberry") {
            on("Hit") {
                val t = pokemon
                val m = activeMove
                if (m != null && t.getMoveHitData(m).typeMod > 0) {
                    if (t.eatItem()) heal(t.baseMaxhp / 4.0)
                }
                Unit
            }
            on("TryEatItem") {
                if (!Js.truthy(battle.runEvent("TryHeal", pokemon, null, self, pokemon.baseMaxhp / 4.0))) false else Unit
            }
            on("Eat") { Unit }
        }
        for (id in MEGA_STONES) {
            item(id) {
                on("TakeItem") {
                    @Suppress("UNCHECKED_CAST")
                    val megaStone: Map<String, Any?>? = (relay as EffectLike).data("megaStone") as? Map<String, Any?>
                    Js.truthy(megaStone?.get(pokemon.baseSpecies.baseSpecies)).not()
                }
            }
        }
    }

    private fun HookRegistrar.plate(id: String, type: String) = item(id) {
        on("BasePower") { if (activeMove?.type == type) chainModify(intArrayOf(4915, 4096)) else Unit }
        on("TakeItem") {
            val src = sourceMon
            if ((src != null && src.baseSpecies.num == 493) || pokemon.baseSpecies.num == 493) false else true
        }
    }

    private fun HookRegistrar.typeBooster(id: String, type: String) = item(id) {
        on("BasePower") { if (activeMove?.type == type) chainModify(intArrayOf(4915, 4096)) else Unit }
    }

    private fun HookRegistrar.mask(id: String, forme: String) = item(id) {
        on("BasePower") { if (pokemon.baseSpecies.name.startsWith(forme)) chainModify(intArrayOf(4915, 4096)) else Unit }
        on("TakeItem") { if (pokemon.baseSpecies.baseSpecies == "Ogerpon") false else true }
    }

    private fun HookRegistrar.critItem(id: String) = item(id) {
        on("ModifyCritRatio") { Js.number(relayNum + 1) }
    }

    private fun HookRegistrar.seed(id: String, terrain: String) = item(id) {
        on("Start") {
            if (!pokemon.ignoringItem() && field.isTerrain(terrain)) pokemon.useItem()
            Unit
        }
        on("TerrainChange") {
            if (field.isTerrain(terrain)) pokemon.useItem()
            Unit
        }
    }

    private fun HookRegistrar.confuseBerry(id: String, minus: String) = item(id) {
        on("Update") {
            if (pinchHp(pokemon)) pokemon.eatItem()
            Unit
        }
        on("TryEatItem") {
            if (!Js.truthy(battle.runEvent("TryHeal", pokemon, null, self, pokemon.baseMaxhp / 3.0))) false else Unit
        }
        on("Eat") {
            val p = pokemon
            heal(p.baseMaxhp / 3.0)
            if (p.getNature()?.minus == minus) p.addVolatile("confusion")
            Unit
        }
    }

    private fun HookRegistrar.statusBerry(id: String, statuses: List<String>) = item(id) {
        on("Update") {
            if (pokemon.status in statuses) pokemon.eatItem()
            Unit
        }
        on("Eat") {
            if (pokemon.status in statuses) pokemon.cureStatus()
            Unit
        }
    }

    private fun HookRegistrar.resistBerry(id: String, type: String) = item(id) {
        on("SourceModifyDamage") { resistBerryModify(this, type) }
        on("Eat") { Unit }
    }

    private fun resistBerryModify(call: HookCall, type: String): Any? = with(call) {
        val t = sourceMon!!
        val m = move
        if (m.type == type && t.getMoveHitData(m).typeMod > 0) {
            val hitSub = t.volatiles["substitute"] != null && !m.flag("bypasssub") && !m.infiltrates
            if (hitSub) return@with Unit
            if (t.eatItem()) {
                add("-enditem", t, self, "[weaken]")
                return@with chainModify(0.5)
            }
        }
        Unit
    }

    private fun HookRegistrar.halfSpeed(id: String) = item(id) {
        on("ModifySpe") { chainModify(0.5) }
    }

    private fun HookRegistrar.pinchBerry(id: String, stat: String) = item(id) {
        on("Update") {
            if (pinchHp(pokemon)) pokemon.eatItem()
            Unit
        }
        on("Eat") { boost(mapOf(stat to 1)); Unit }
    }

    private fun HookRegistrar.retaliateBerry(id: String, category: String) = item(id) {
        on("DamagingHit") {
            val t = pokemon
            val src = sourceMon!!
            if (move.category == category && src.hp != 0 && src.isActive && !src.hasAbility("magicguard")) {
                if (t.eatItem()) battle.damage(src.baseMaxhp / (if (t.hasAbility("ripen")) 4.0 else 8.0), src, t)
            }
            Unit
        }
        on("Eat") { Unit }
    }
}
