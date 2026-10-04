package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** The most used held items with code in `data/items.js`, ported first. */
object TopItems : HookSet() {
    override fun HookRegistrar.define() {
        item("focussash") {
            on("Damage") {
                val t = pokemon
                val e = sourceEffect
                if (t.hp == t.maxhp && relayNum >= t.hp && e != null && e.effectType == "Move") {
                    if (t.useItem()) return@on t.hp - 1
                }
                Unit
            }
        }
        item("leftovers") {
            on("Residual") { heal(pokemon.baseMaxhp / 16.0); Unit }
        }
        item("assaultvest") {
            on("ModifySpD") { chainModify(1.5) }
            on("DisableMove") {
                for (slot in pokemon.moveSlots) {
                    val m = dex.move(slot.id)
                    if (m != null && m.category == "Status" && m.id != "mefirst") pokemon.disableMove(slot.id)
                }
                Unit
            }
        }
        item("lifeorb") {
            on("ModifyDamage") { chainModify(intArrayOf(5324, 4096)) }
            on("AfterMoveSecondarySelf") {
                val src = pokemon
                val m = activeMove
                if (src !== source && m != null && m.category != "Status" && !src.forceSwitchFlag) {
                    battle.damage(src.baseMaxhp / 10.0, src, src, dex.item("lifeorb"))
                }
                Unit
            }
        }
        item("sitrusberry") {
            on("Update") {
                if (pokemon.hp <= pokemon.maxhp / 2.0) pokemon.eatItem()
                Unit
            }
            on("TryEatItem") {
                if (!Js.truthy(battle.runEvent("TryHeal", pokemon, null, self, pokemon.baseMaxhp / 4.0))) false else Unit
            }
            on("Eat") { heal(pokemon.baseMaxhp / 4.0); Unit }
        }
        item("eviolite") {
            on("ModifyDef") { if (pokemon.baseSpecies.nfe) chainModify(1.5) else Unit }
            on("ModifySpD") { if (pokemon.baseSpecies.nfe) chainModify(1.5) else Unit }
        }
        item("covertcloak") {
            on("ModifySecondaries") {
                @Suppress("UNCHECKED_CAST")
                (relay as List<HitData>).filter { it.hitSelf != null || Js.truthy(it.data("dustproof")) }
            }
        }
        item("rockyhelmet") {
            on("DamagingHit") {
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, pokemon)) battle.damage(src.baseMaxhp / 6.0, src, pokemon)
                Unit
            }
        }
        choice("choicescarf", "ModifySpe")
        choice("choiceband", "ModifyAtk")
        choice("choicespecs", "ModifySpA")
        item("mentalherb") {
            on("Update") {
                val p = pokemon
                val conditions = listOf("attract", "taunt", "encore", "torment", "disable", "healblock")
                for (first in conditions) {
                    if (p.volatiles[first] != null) {
                        if (!p.useItem()) return@on Unit
                        for (second in conditions) {
                            p.removeVolatile(second)
                            if (first == "attract" && second == "attract") add("-end", p, "move: Attract", "[from] item: Mental Herb")
                        }
                        return@on Unit
                    }
                }
                Unit
            }
        }
        item("boosterenergy") {
            on("Start") { state["started"] = true; Unit }
            on("Update") {
                val p = pokemon
                if (!Js.truthy(state["started"]) || p.transformed) return@on Unit
                if (battle.queue.peek(true)?.choice == "runSwitch") return@on Unit
                if (p.hasAbility("protosynthesis") && !field.isWeather("sunnyday") && p.useItem()) p.addVolatile("protosynthesis")
                if (p.hasAbility("quarkdrive") && !field.isTerrain("electricterrain") && p.useItem()) p.addVolatile("quarkdrive")
                Unit
            }
            on("TakeItem") {
                val src = sourceMon ?: target as Pokemon
                val tags = src.baseSpecies.list("tags")?.map { it as String } ?: emptyList()
                "Paradox" !in tags
            }
        }
        item("loadeddice") {
            on("ModifyMove") { (relay as ActiveMove).multiaccuracy = false; Unit }
        }
        item("clearamulet") {
            on("TryBoost") { TopAbilities.clearBodyTryBoost(this, "[from] item: Clear Amulet") }
        }
        item("safetygoggles") {
            on("Immunity") { if (relay == "sandstorm" || relay == "hail" || relay == "powder") false else Unit }
            on("TryHit") {
                val p = pokemon
                if (move.flag("powder") && p !== source && dex.notImmune("powder", p.getTypes())) {
                    add("-activate", p, "item: Safety Goggles", move.name)
                    null
                } else Unit
            }
        }
        item("weaknesspolicy") {
            on("DamagingHit") {
                val m = move
                if (!Js.truthy(m.damage) && !m.declares("damageCallback") && pokemon.getMoveHitData(m).typeMod > 0) pokemon.useItem()
                Unit
            }
        }
    }

    private fun HookRegistrar.choice(id: String, statEvent: String) = item(id) {
        on("Start") { pokemon.removeVolatile("choicelock"); Unit }
        on("ModifyMove") { pokemon.addVolatile("choicelock"); Unit }
        on(statEvent) { if (pokemon.volatiles["dynamax"] != null) Unit else chainModify(1.5) }
    }
}
