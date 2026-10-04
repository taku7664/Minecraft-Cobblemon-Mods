package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove

/** The most used moves with code in `data/moves.js`, ported first. */
object TopMoves : HookSet() {
    override fun HookRegistrar.define() {
        move("protect") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "Protect"); Unit }
                on("TryHit") { protectTryHit(this.pokemon, sourceMon!!, move, "move: Protect") }
            }
        }
        move("knockoff") {
            on("BasePower") {
                val defender = sourceMon!!
                val item = defender.getItem()
                if (!Js.truthy(battle.singleEvent("TakeItem", item, defender.itemState, defender, defender, move, item))) return@on Unit
                if (item.id.isNotEmpty()) chainModify(1.5) else Unit
            }
            on("AfterHit") {
                val src = sourceMon!!
                if (src.hp != 0) {
                    val item = pokemon.takeItem()
                    if (item is EffectLike) add("-enditem", pokemon, item.name, "[from] move: Knock Off", "[of] $src")
                }
                Unit
            }
        }
        move("trickroom") {
            condition {
                callback("durationCallback") {
                    val source = target as? Pokemon
                    if (source?.hasAbility("persistent") == true) {
                        add("-activate", source, "ability: Persistent", "[move] Trick Room")
                        7
                    } else 5
                }
                on("FieldStart") {
                    val src = sourceMon
                    if (src?.hasAbility("persistent") == true) add("-fieldstart", "move: Trick Room", "[of] $src", "[persistent]")
                    else add("-fieldstart", "move: Trick Room", "[of] $src")
                    Unit
                }
                on("FieldRestart") { field.removePseudoWeather("trickroom"); Unit }
                on("FieldEnd") { add("-fieldend", "move: Trick Room"); Unit }
            }
        }
        move("taunt") {
            condition {
                on("Start") {
                    if (pokemon.activeTurns != 0 && battle.queue.willMove(pokemon) == null) state.duration = (state.duration ?: 0) + 1
                    add("-start", target, "move: Taunt")
                    Unit
                }
                on("End") { add("-end", target, "move: Taunt"); Unit }
                on("DisableMove") {
                    for (slot in pokemon.moveSlots) {
                        val m = dex.move(slot.id)
                        if (m != null && m.category == "Status" && m.id != "mefirst") pokemon.disableMove(slot.id)
                    }
                    Unit
                }
                on("BeforeMove") {
                    val m = move
                    if (!Js.truthy(m.isZ) && !Js.truthy(m.isMax) && m.category == "Status" && m.id != "mefirst") {
                        add("cant", pokemon, "move: Taunt", m)
                        false
                    } else Unit
                }
            }
        }
        move("terablast") {
            callback("basePowerCallback") { if (pokemon.terastallized == "Stellar") 100 else move.basePower }
            on("PrepareHit") {
                val src = sourceMon!!
                if (src.terastallized != null) battle.attrLastMove("[anim] Tera Blast " + src.teraType)
                Unit
            }
            on("ModifyType") {
                val m = relay as ActiveMove
                if (pokemon.terastallized != null) m.type = pokemon.teraType.take(1).uppercase() + pokemon.teraType.drop(1)
                Unit
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                val p = pokemon
                if (p.terastallized != null && p.getStat("atk", false, true) > p.getStat("spa", false, true)) m.category = "Physical"
                if (p.terastallized == "Stellar") m.self = LiteralHit(hitBoosts = linkedMapOf("atk" to -1, "spa" to -1))
                Unit
            }
        }
        move("tailwind") {
            condition {
                callback("durationCallback") {
                    val src = sourceMon
                    if (src?.hasAbility("persistent") == true) {
                        add("-activate", src, "ability: Persistent", "[move] Tailwind")
                        6
                    } else 4
                }
                on("SideStart") {
                    if (sourceMon?.hasAbility("persistent") == true) add("-sidestart", target, "move: Tailwind", "[persistent]")
                    else add("-sidestart", target, "move: Tailwind")
                    Unit
                }
                on("ModifySpe") { chainModify(2) }
                on("SideEnd") { add("-sideend", target, "move: Tailwind"); Unit }
            }
        }
        move("fakeout") {
            on("Try") {
                if (pokemon.activeMoveActions > 1) {
                    hint("Fake Out only works on your first turn out.")
                    false
                } else Unit
            }
        }
        move("substitute") {
            on("TryHit") {
                val src = pokemon
                if (src.volatiles["substitute"] != null) {
                    add("-fail", src, "move: Substitute")
                    return@on BattleActions.NOT_FAIL
                }
                if (src.hp <= src.maxhp / 4.0 || src.maxhp == 1) {
                    add("-fail", src, "move: Substitute", "[weak]")
                    return@on BattleActions.NOT_FAIL
                }
                Unit
            }
            on("Hit") { battle.directDamage(pokemon.maxhp / 4.0, pokemon); Unit }
            condition {
                on("Start") {
                    val t = pokemon
                    if (sourceEffect?.id == "shedtail") add("-start", t, "Substitute", "[from] move: Shed Tail")
                    else add("-start", t, "Substitute")
                    state["hp"] = t.maxhp / 4
                    t.volatiles["partiallytrapped"]?.let { trap ->
                        add("-end", t, trap.sourceEffect, "[partiallytrapped]", "[silent]")
                        t.volatiles.remove("partiallytrapped")
                    }
                    Unit
                }
                on("TryPrimaryHit") {
                    val t = pokemon
                    val src = sourceMon!!
                    val m = effect as ActiveMove
                    if (t === src || m.flag("bypasssub") || m.infiltrates) return@on Unit
                    var damage = battle.actions.getDamage(src, t, m)
                    if (!Js.truthy(damage) && damage != 0) {
                        add("-fail", src)
                        battle.attrLastMove("[still]")
                        return@on null
                    }
                    damage = battle.runEvent("SubDamage", t, src, m, damage)
                    if (!Js.truthy(damage)) return@on damage
                    val sub = t.volatiles.getValue("substitute")
                    var dealt = Js.int(damage)
                    if (dealt > sub.int("hp")) dealt = sub.int("hp")
                    sub["hp"] = sub.int("hp") - dealt
                    src.lastDamage = dealt
                    if (sub.int("hp") <= 0) {
                        if (Js.truthy(m.ohko)) add("-ohko")
                        t.removeVolatile("substitute")
                    } else {
                        add("-activate", t, "move: Substitute", "[damage]")
                    }
                    if (m.recoil != null || m.id == "chloroblast") {
                        battle.damage(battle.actions.calcRecoilDamage(dealt, m, src), src, t, dex.conditionById("recoil"))
                    }
                    m.drain?.let { drain -> battle.heal(Math.ceil(dealt.toDouble() * drain[0] / drain[1]), src, t, dex.conditionById("drain")) }
                    battle.singleEvent("AfterSubDamage", m, null, t, src, m, dealt)
                    battle.runEvent("AfterSubDamage", t, src, m, dealt)
                    BattleActions.HIT_SUBSTITUTE
                }
                on("End") { add("-end", target, "Substitute"); Unit }
            }
        }
        move("suckerpunch") {
            on("Try") {
                val action = battle.queue.willMove(sourceMon!!)
                val m = if (action?.choice == "move") action.move else null
                if (m == null || (m.category == "Status" && m.id != "mefirst") || sourceMon!!.volatiles["mustrecharge"] != null) false else Unit
            }
        }
        move("encore") {
            condition {
                on("Start") {
                    val t = pokemon
                    var m: EffectLike = t.lastMove ?: return@on false
                    if (t.volatiles["dynamax"] != null) return@on false
                    val last = m as ActiveMove
                    if (Js.truthy(last.isMax) && last.baseMove != null) m = dex.move(last.baseMove!!)!!
                    val index = t.moves.indexOf(m.id)
                    if (Js.truthy(m.data("isZ")) || m.flag("failencore") || index < 0 || t.moveSlots[index].pp <= 0) return@on false
                    state["move"] = m.id
                    add("-start", t, "Encore")
                    if (battle.queue.willMove(t) == null) state.duration = (state.duration ?: 0) + 1
                    Unit
                }
                on("OverrideAction") { if (move.id != state["move"]) state["move"] else Unit }
                on("Residual") {
                    val t = pokemon
                    val locked = state["move"] as String
                    val index = t.moves.indexOf(locked)
                    if (index < 0 || t.moveSlots[index].pp <= 0) t.removeVolatile("encore")
                    Unit
                }
                on("End") { add("-end", target, "Encore"); Unit }
                on("DisableMove") {
                    val locked = state["move"] as? String
                    if (locked == null || pokemon.hasMove(locked) == null) return@on Unit
                    for (slot in pokemon.moveSlots) if (slot.id != locked) pokemon.disableMove(slot.id)
                    Unit
                }
            }
        }
        move("helpinghand") {
            on("TryHit") { if (!pokemon.newlySwitched && battle.queue.willMove(pokemon) == null) false else Unit }
            condition {
                on("Start") {
                    state["multiplier"] = 1.5
                    add("-singleturn", target, "Helping Hand", "[of] $source")
                    Unit
                }
                on("Restart") {
                    state["multiplier"] = Js.num(state["multiplier"]) * 1.5
                    add("-singleturn", target, "Helping Hand", "[of] $source")
                    Unit
                }
                on("BasePower") { chainModify(Js.num(state["multiplier"])) }
            }
        }
        move("stealthrock") {
            condition {
                on("SideStart") { add("-sidestart", target as Side, "move: Stealth Rock"); Unit }
                on("EntryHazard") {
                    val p = pokemon
                    if (p.hasItem("heavydutyboots")) return@on Unit
                    val typeMod = p.runEffectiveness(dex.activeMove("stealthrock")).coerceIn(-6, 6)
                    damage(p.maxhp * Math.pow(2.0, typeMod.toDouble()) / 8)
                    Unit
                }
            }
        }
    }

    /** The shared body of Protect-style `onTryHit` handlers, up to the move-specific extra effect. */
    fun protectTryHit(target: Pokemon, source: Pokemon, move: ActiveMove, activate: String): Any? {
        val battle = target.battle
        if (!move.flag("protect")) {
            if (move.id in listOf("gmaxoneblow", "gmaxrapidflow")) return Unit
            if (Js.truthy(move.isZ) || Js.truthy(move.isMax)) target.getMoveHitData(move).zBrokeProtect = true
            return Unit
        }
        if (move.smartTarget == true) move.smartTarget = false else battle.add("-activate", target, activate)
        val lockedmove = source.getVolatile("lockedmove")
        if (lockedmove != null && source.volatiles["lockedmove"]?.duration == 2) source.volatiles.remove("lockedmove")
        return BattleActions.NOT_FAIL
    }
}
