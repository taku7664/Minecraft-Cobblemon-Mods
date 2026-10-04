package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.Action
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove
import kotlin.math.ceil
import kotlin.math.floor

/** Moves with code in `data/moves.js`, port list A continued. */
object PortMovesA2 : HookSet() {
    private val NOT_FAIL = BattleActions.NOT_FAIL

    private val HAZARDS = listOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")
    private val SCREENS_AND_HAZARDS = listOf("reflect", "lightscreen", "auroraveil", "safeguard", "mist", "spikes", "toxicspikes",
        "stealthrock", "stickyweb")

    override fun HookRegistrar.define() {
        move("gmaxstonesurge") {
            self {
                on("Hit") {
                    for (side in pokemon.side.foeSidesWithConditions()) side.addSideCondition("stealthrock")
                    Unit
                }
            }
        }
        move("gmaxtartness") {
            self {
                on("Hit") {
                    for (p in pokemon.foes()) battle.boost(mapOf("evasion" to -1), p)
                    Unit
                }
            }
        }
        gmaxResidual("gmaxvolcalith", "G-Max Volcalith", "Rock")
        gmaxResidual("gmaxcannonade", "G-Max Cannonade", "Water")
        move("gmaxwindrage") {
            self {
                on("Hit") {
                    val src = pokemon
                    var success = false
                    for (targetCondition in SCREENS_AND_HAZARDS) {
                        if (src.side.foe.removeSideCondition(targetCondition)) {
                            if (targetCondition !in HAZARDS) continue
                            add("-sideend", src.side.foe, dex.condition(targetCondition).name, "[from] move: G-Max Wind Rage", "[of] $src")
                            success = true
                        }
                    }
                    for (sideCondition in HAZARDS) {
                        if (src.side.removeSideCondition(sideCondition)) {
                            add("-sideend", src.side, dex.condition(sideCondition).name, "[from] move: G-Max Wind Rage", "[of] $src")
                            success = true
                        }
                    }
                    field.clearTerrain()
                    success
                }
            }
        }
        move("holdback") {
            on("Damage") { if (relayNum >= pokemon.hp) pokemon.hp - 1 else Unit }
        }
        move("struggle") {
            on("ModifyMove") {
                (relay as ActiveMove).type = "???"
                add("-activate", pokemon, "move: Struggle")
                Unit
            }
        }
        move("endeavor") {
            callback("damageCallback") { sourceMon!!.getUndynamaxedHP() - pokemon.hp }
            on("TryImmunity") { sourceMon!!.hp < pokemon.hp }
        }
        move("yawn") {
            on("TryHit") {
                if (pokemon.status.isNotEmpty() || !pokemon.runStatusImmunity("slp")) false else Unit
            }
            condition {
                on("Start") { add("-start", target, "move: Yawn", "[of] $source"); Unit }
                on("End") {
                    add("-end", target, "move: Yawn", "[silent]")
                    pokemon.trySetStatus("slp", state.source)
                    Unit
                }
            }
        }
        move("leechseed") {
            on("TryImmunity") { !pokemon.hasType("Grass") }
            condition {
                on("Start") { add("-start", target, "move: Leech Seed"); Unit }
                on("Residual") {
                    val p = pokemon
                    val slot = p.volatiles["leechseed"]?.sourceSlot ?: return@on Unit
                    val t = PortMovesA.getAtSlot(battle, slot)
                    if (t == null || t.fainted || t.hp <= 0) return@on Unit
                    val damage = battle.damage(p.baseMaxhp / 8.0, p, t)
                    if (Js.truthy(damage)) battle.heal(Js.num(damage), t, p)
                    Unit
                }
            }
        }
        screen("lightscreen", "move: Light Screen", "Special")
        move("auroraveil") {
            on("Try") { field.isWeather(listOf("hail", "snow")) }
            condition {
                callback("durationCallback") { if (sourceMon?.hasItem("lightclay") == true) 8 else 5 }
                on("AnyModifyDamage") {
                    val src = pokemon
                    val t = sourceMon ?: return@on Unit
                    val m = move
                    if (t !== src && (state.target as Side).hasAlly(t)) {
                        if ((t.side.getSideCondition("reflect") != null && m.category == "Physical") ||
                            (t.side.getSideCondition("lightscreen") != null && m.category == "Special")) return@on Unit
                        if (!t.getMoveHitData(m).crit && !m.infiltrates) {
                            return@on if (battle.activePerHalf > 1) chainModify(intArrayOf(2732, 4096)) else chainModify(0.5)
                        }
                    }
                    Unit
                }
                on("SideStart") { add("-sidestart", target, "move: Aurora Veil"); Unit }
                on("SideEnd") { add("-sideend", target, "move: Aurora Veil"); Unit }
            }
        }
        move("blizzard") {
            on("ModifyMove") {
                if (field.isWeather(listOf("hail", "snow"))) (relay as ActiveMove).accuracy = true
                Unit
            }
        }
        move("thunder") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                when (sourceMon?.effectiveWeather()) {
                    "raindance", "primordialsea" -> m.accuracy = true
                    "sunnyday", "desolateland" -> m.accuracy = 50
                }
                Unit
            }
        }
        move("alluringvoice") {
            secondary {
                on("Hit") {
                    val t = targetMon
                    if (t?.statsRaisedThisTurn == true) t.addVolatile("confusion", sourceMon, move)
                    Unit
                }
            }
        }
        move("rest") {
            on("Try") {
                val s = pokemon
                if (s.status == "slp" || s.hasAbility("comatose")) return@on false
                if (s.hp == s.maxhp) {
                    add("-fail", s, "heal")
                    return@on null
                }
                if (s.hasAbility(listOf("insomnia", "vitalspirit"))) {
                    add("-fail", s, "[from] ability: " + s.getAbility().name, "[of] $s")
                    return@on null
                }
                Unit
            }
            on("Hit") {
                val t = pokemon
                val result = t.setStatus("slp", sourceMon, move)
                if (!Js.truthy(result)) return@on result
                t.statusState["time"] = 3
                t.statusState["startTime"] = 3
                heal(t.maxhp)
                Unit
            }
        }
        move("icespinner") {
            on("AfterHit") { if (sourceMon!!.hp != 0) field.clearTerrain(); Unit }
            on("AfterSubDamage") { if (sourceMon!!.hp != 0) field.clearTerrain(); Unit }
        }
        move("counter") {
            callback("damageCallback") {
                val v = pokemon.volatiles["counter"] ?: return@callback 0
                val d = v["damage"]
                if (Js.truthy(d)) d else 1
            }
            callback("beforeTurnCallback") { pokemon.addVolatile("counter"); Unit }
            on("Try") {
                val v = pokemon.volatiles["counter"] ?: return@on false
                if (v["slot"] == null) false else Unit
            }
            condition {
                on("Start") {
                    state["slot"] = null
                    state["damage"] = 0
                    Unit
                }
                on("RedirectTarget") {
                    if (move.id != "counter") return@on Unit
                    val slot = state["slot"] as? String
                    if (target !== state.target || slot.isNullOrEmpty()) return@on Unit
                    PortMovesA.getAtSlot(battle, slot)
                }
                on("DamagingHit") {
                    val t = pokemon
                    val s = sourceMon!!
                    if (!s.isAlly(t) && move.category == "Physical") {
                        state["slot"] = s.getSlot()
                        state["damage"] = Js.number(2 * relayNum)
                    }
                    Unit
                }
            }
        }
        move("acrobatics") {
            callback("basePowerCallback") { if (pokemon.item.isEmpty()) move.basePower * 2 else move.basePower }
        }
        move("poltergeist") {
            on("Try") { sourceMon!!.item.isNotEmpty() }
            on("TryHit") { add("-activate", target, "move: Poltergeist", dex.item(pokemon.item).name); Unit }
        }
        move("solarbeam") {
            on("TryMove") {
                val attacker = pokemon
                val defender = sourceMon
                val m = move
                if (attacker.removeVolatile(m.id)) return@on Unit
                add("-prepare", attacker, m.name)
                if (attacker.effectiveWeather() in listOf("sunnyday", "desolateland")) {
                    battle.attrLastMove("[still]")
                    battle.addMove("-anim", attacker, m.name, defender)
                    return@on Unit
                }
                if (!Js.truthy(battle.runEvent("ChargeMove", attacker, defender, m))) return@on Unit
                attacker.addVolatile("twoturnmove", defender)
                null
            }
            on("BasePower") {
                if (pokemon.effectiveWeather() in listOf("raindance", "primordialsea", "sandstorm", "hail", "snow")) chainModify(0.5) else Unit
            }
        }
        move("throatchop") {
            condition {
                on("Start") { add("-start", target, "Throat Chop", "[silent]"); Unit }
                on("DisableMove") {
                    for (slot in pokemon.moveSlots) {
                        if (dex.move(slot.id)?.flag("sound") == true) pokemon.disableMove(slot.id)
                    }
                    Unit
                }
                on("BeforeMove") {
                    val m = move
                    if (!Js.truthy(m.isZ) && !Js.truthy(m.isMax) && m.flag("sound")) {
                        add("cant", pokemon, "move: Throat Chop")
                        false
                    } else Unit
                }
                on("ModifyMove") {
                    val m = relay as ActiveMove
                    if (!Js.truthy(m.isZ) && !Js.truthy(m.isMax) && m.flag("sound")) {
                        add("cant", pokemon, "move: Throat Chop")
                        false
                    } else Unit
                }
                on("End") { add("-end", target, "Throat Chop", "[silent]"); Unit }
            }
            secondary { on("Hit") { pokemon.addVolatile("throatchop"); Unit } }
        }
        move("minimize") {
            condition {
                on("Restart") { null }
                on("SourceModifyDamage") { if (move.id in MINIMIZE_MOVES) chainModify(2) else Unit }
                on("Accuracy") { if (move.id in MINIMIZE_MOVES) true else relay }
            }
        }
        move("defensecurl") {
            condition { on("Restart") { null } }
        }
        move("rapidspin") {
            on("AfterHit") { rapidSpin(this); Unit }
            on("AfterSubDamage") { rapidSpin(this); Unit }
        }
        move("meteorbeam") {
            on("TryMove") {
                val attacker = pokemon
                val defender = sourceMon
                val m = move
                if (attacker.removeVolatile(m.id)) return@on Unit
                add("-prepare", attacker, m.name)
                battle.boost(mapOf("spa" to 1), attacker, attacker, m)
                if (!Js.truthy(battle.runEvent("ChargeMove", attacker, defender, m))) return@on Unit
                attacker.addVolatile("twoturnmove", defender)
                null
            }
        }
        move("electroshot") {
            on("TryMove") {
                val attacker = pokemon
                val defender = sourceMon
                val m = move
                if (attacker.removeVolatile(m.id)) return@on Unit
                add("-prepare", attacker, m.name)
                battle.boost(mapOf("spa" to 1), attacker, attacker, m)
                if (attacker.effectiveWeather() in listOf("raindance", "primordialsea")) {
                    battle.attrLastMove("[still]")
                    battle.addMove("-anim", attacker, m.name, defender)
                    return@on Unit
                }
                if (!Js.truthy(battle.runEvent("ChargeMove", attacker, defender, m))) return@on Unit
                attacker.addVolatile("twoturnmove", defender)
                null
            }
        }
        screenBreaker("psychicfangs")
        move("ragingbull") {
            on("TryHit") {
                val side = pokemon.side
                side.removeSideCondition("reflect")
                side.removeSideCondition("lightscreen")
                side.removeSideCondition("auroraveil")
                Unit
            }
            on("ModifyType") {
                val m = relay as ActiveMove
                when (pokemon.species.name) {
                    "Tauros-Paldea-Combat" -> m.type = "Fighting"
                    "Tauros-Paldea-Blaze" -> m.type = "Fire"
                    "Tauros-Paldea-Aqua" -> m.type = "Water"
                }
                Unit
            }
        }
        move("clearsmog") {
            on("Hit") {
                pokemon.clearBoosts()
                add("-clearboost", target)
                Unit
            }
        }
        move("healpulse") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val success = if (s.hasAbility("megalauncher")) Js.truthy(heal(battle.modify(t.baseMaxhp, 0.75)))
                else Js.truthy(heal(ceil(t.baseMaxhp * 0.5)))
                if (success && !t.isAlly(s)) t.staleness = "external"
                if (!success) {
                    add("-fail", t, "heal")
                    return@on NOT_FAIL
                }
                success
            }
        }
        move("photongeyser") {
            on("ModifyMove") {
                val p = pokemon
                if (p.getStat("atk", false, true) > p.getStat("spa", false, true)) (relay as ActiveMove).category = "Physical"
                Unit
            }
        }
        move("finalgambit") {
            callback("damageCallback") {
                val damage = pokemon.hp
                pokemon.faint()
                damage
            }
        }
        move("gravity") {
            condition {
                callback("durationCallback") {
                    val s = target as? Pokemon
                    if (s?.hasAbility("persistent") == true) {
                        add("-activate", s, "ability: Persistent", "[move] Gravity")
                        7
                    } else 5
                }
                on("FieldStart") {
                    if (sourceMon?.hasAbility("persistent") == true) add("-fieldstart", "move: Gravity", "[persistent]")
                    else add("-fieldstart", "move: Gravity")
                    for (p in battle.getAllActive()) {
                        var applies = false
                        if (p.removeVolatile("bounce") || p.removeVolatile("fly")) {
                            applies = true
                            battle.queue.cancelMove(p)
                            p.removeVolatile("twoturnmove")
                        }
                        if (p.volatiles["skydrop"] != null) {
                            applies = true
                            battle.queue.cancelMove(p)
                            if (p.volatiles["skydrop"]!!.source != null) {
                                add("-end", p.volatiles["twoturnmove"]?.source, "Sky Drop", "[interrupt]")
                            }
                            p.removeVolatile("skydrop")
                            p.removeVolatile("twoturnmove")
                        }
                        if (p.volatiles["magnetrise"] != null) {
                            applies = true
                            p.volatiles.remove("magnetrise")
                        }
                        if (p.volatiles["telekinesis"] != null) {
                            applies = true
                            p.volatiles.remove("telekinesis")
                        }
                        if (applies) add("-activate", p, "move: Gravity")
                    }
                    Unit
                }
                on("ModifyAccuracy") {
                    if (!Js.isNumber(relay)) return@on Unit
                    chainModify(intArrayOf(6840, 4096))
                }
                on("DisableMove") {
                    for (slot in pokemon.moveSlots) {
                        if (dex.move(slot.id)?.flag("gravity") == true) pokemon.disableMove(slot.id)
                    }
                    Unit
                }
                on("BeforeMove") {
                    if (move.flag("gravity") && !Js.truthy(move.isZ)) {
                        add("cant", pokemon, "move: Gravity", move)
                        false
                    } else Unit
                }
                on("ModifyMove") {
                    val m = relay as ActiveMove
                    if (m.flag("gravity") && !Js.truthy(m.isZ)) {
                        add("cant", pokemon, "move: Gravity", m)
                        false
                    } else Unit
                }
                on("FieldEnd") { add("-fieldend", "move: Gravity"); Unit }
            }
        }
        move("grassyglide") {
            on("ModifyPriority") {
                if (field.isTerrain("grassyterrain") && pokemon.isGrounded() == true) Js.number(relayNum + 1) else Unit
            }
        }
        move("sleeptalk") {
            on("Try") { pokemon.status == "slp" || pokemon.hasAbility("comatose") }
            on("Hit") {
                val p = pokemon
                val moves = ArrayList<String>()
                for (slot in p.moveSlots) {
                    val moveid = slot.id
                    if (moveid.isEmpty()) continue
                    val m = dex.move(moveid) ?: continue
                    if (m.flag("nosleeptalk") || m.flag("charge") || (Js.truthy(m.isZ) && m.basePower != 1) || Js.truthy(m.isMax)) continue
                    moves.add(moveid)
                }
                var randomMove = ""
                if (moves.isNotEmpty()) randomMove = battle.sample(moves)
                if (randomMove.isEmpty()) return@on false
                battle.actions.useMove(dex.activeMove(randomMove), p, null, targetUndefined = true)
                Unit
            }
        }
        move("transform") {
            on("Hit") { if (!sourceMon!!.transformInto(pokemon)) false else Unit }
        }
        move("triattack") {
            secondary {
                on("Hit") {
                    val t = pokemon
                    val s = sourceMon
                    when (random(3)) {
                        0 -> t.trySetStatus("brn", s)
                        1 -> t.trySetStatus("par", s)
                        else -> t.trySetStatus("frz", s)
                    }
                    Unit
                }
            }
        }
        move("direclaw") {
            secondary {
                on("Hit") {
                    val t = pokemon
                    val s = sourceMon
                    when (random(3)) {
                        0 -> t.trySetStatus("psn", s)
                        1 -> t.trySetStatus("par", s)
                        else -> t.trySetStatus("slp", s)
                    }
                    Unit
                }
            }
        }
        move("defog") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                var success = false
                if (t.volatiles["substitute"] == null || move.infiltrates) success = Js.truthy(boost(mapOf("evasion" to -1)))
                for (targetCondition in SCREENS_AND_HAZARDS + "gmaxsteelsurge") {
                    if (t.side.removeSideCondition(targetCondition)) {
                        if (targetCondition !in HAZARDS) continue
                        add("-sideend", t.side, dex.condition(targetCondition).name, "[from] move: Defog", "[of] $s")
                        success = true
                    }
                }
                for (sideCondition in HAZARDS) {
                    if (s.side.removeSideCondition(sideCondition)) {
                        add("-sideend", s.side, dex.condition(sideCondition).name, "[from] move: Defog", "[of] $s")
                        success = true
                    }
                }
                field.clearTerrain()
                success
            }
        }
        simpleCharge("phantomforce")
        simpleCharge("shadowforce")
        move("aurawheel") {
            on("Try") {
                val s = pokemon
                if (s.species.baseSpecies == "Morpeko") return@on Unit
                battle.attrLastMove("[still]")
                add("-fail", s, "move: Aura Wheel")
                hint("Only a Pokemon whose form is Morpeko or Morpeko-Hangry can use this move.")
                null
            }
            on("ModifyType") {
                (relay as ActiveMove).type = if (pokemon.species.name == "Morpeko-Hangry") "Dark" else "Electric"
                Unit
            }
        }
        move("psyblade") {
            on("BasePower") { if (field.isTerrain("electricterrain")) chainModify(1.5) else Unit }
        }
        move("barbbarrage") {
            on("BasePower") {
                val t = sourceMon!!
                if (t.status == "psn" || t.status == "tox") chainModify(2) else Unit
            }
        }
        move("hyperspacefury") {
            on("Try") {
                val s = pokemon
                if (s.species.name == "Hoopa-Unbound") return@on Unit
                hint("Only a Pokemon whose form is Hoopa Unbound can use this move.")
                if (s.species.name == "Hoopa") {
                    battle.attrLastMove("[still]")
                    add("-fail", s, "move: Hyperspace Fury", "[forme]")
                    return@on null
                }
                battle.attrLastMove("[still]")
                add("-fail", s, "move: Hyperspace Fury")
                null
            }
        }
        move("growth") {
            on("ModifyMove") {
                if (pokemon.effectiveWeather() in listOf("sunnyday", "desolateland")) (relay as ActiveMove).boosts = linkedMapOf("atk" to 2, "spa" to 2)
                Unit
            }
        }
        move("tidyup") {
            on("Hit") {
                val p = pokemon
                var success = false
                for (active in battle.getAllActive()) {
                    if (active.removeVolatile("substitute")) success = true
                }
                val sides = listOf(p.side) + p.side.foeSidesWithConditions()
                for (side in sides) {
                    for (sideCondition in HAZARDS) {
                        if (side.removeSideCondition(sideCondition)) {
                            add("-sideend", side, dex.condition(sideCondition).name)
                            success = true
                        }
                    }
                }
                if (success) add("-activate", p, "move: Tidy Up")
                Js.truthy(battle.boost(linkedMapOf("atk" to 1, "spe" to 1), p, p, null, false, true)) || success
            }
        }
        move("quickguard") {
            on("Try") { battle.queue.willAct() != null }
            on("HitSide") { sourceMon!!.addVolatile("stall"); Unit }
            condition {
                on("SideStart") { add("-singleturn", source, "Quick Guard"); Unit }
                on("TryHit") {
                    val m = move
                    if (m.priority <= 0.1) return@on Unit
                    if (!m.flag("protect")) {
                        if (m.id in listOf("gmaxoneblow", "gmaxrapidflow")) return@on Unit
                        if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) pokemon.getMoveHitData(m).zBrokeProtect = true
                        return@on Unit
                    }
                    add("-activate", target, "move: Quick Guard")
                    dropLockedMove(sourceMon!!)
                    NOT_FAIL
                }
            }
        }
        move("banefulbunker") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "move: Protect"); Unit }
                on("TryHit") {
                    val t = pokemon
                    val s = sourceMon!!
                    val m = move
                    if (!m.flag("protect")) {
                        if (m.id in listOf("gmaxoneblow", "gmaxrapidflow")) return@on Unit
                        if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) t.getMoveHitData(m).zBrokeProtect = true
                        return@on Unit
                    }
                    if (m.smartTarget == true) m.smartTarget = false else add("-activate", t, "move: Protect")
                    dropLockedMove(s)
                    if (battle.checkMoveMakesContact(m, s, t)) s.trySetStatus("psn", t)
                    NOT_FAIL
                }
                on("Hit") {
                    val s = sourceMon!!
                    if (move.isZOrMaxPowered && battle.checkMoveMakesContact(move, s, pokemon)) s.trySetStatus("psn", pokemon)
                    Unit
                }
            }
        }
        move("silktrap") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "Protect"); Unit }
                on("TryHit") {
                    val t = pokemon
                    val s = sourceMon!!
                    val m = move
                    if (!m.flag("protect") || m.category == "Status") {
                        if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) t.getMoveHitData(m).zBrokeProtect = true
                        return@on Unit
                    }
                    if (m.smartTarget == true) m.smartTarget = false else add("-activate", t, "move: Protect")
                    dropLockedMove(s)
                    if (battle.checkMoveMakesContact(m, s, t)) battle.boost(mapOf("spe" to -1), s, t, dex.activeMove("Silk Trap"))
                    NOT_FAIL
                }
                on("Hit") {
                    val s = sourceMon!!
                    if (move.isZOrMaxPowered && battle.checkMoveMakesContact(move, s, pokemon)) {
                        battle.boost(mapOf("spe" to -1), s, pokemon, dex.activeMove("Silk Trap"))
                    }
                    Unit
                }
            }
        }
        move("flail") {
            callback("basePowerCallback") {
                val p = pokemon
                val ratio = maxOf(floor(p.hp * 48.0 / p.maxhp).toInt(), 1)
                when {
                    ratio < 2 -> 200
                    ratio < 5 -> 150
                    ratio < 10 -> 100
                    ratio < 17 -> 80
                    ratio < 33 -> 40
                    else -> 20
                }
            }
        }
        move("burningjealousy") {
            secondary {
                on("Hit") {
                    val t = targetMon
                    if (t?.statsRaisedThisTurn == true) t.trySetStatus("brn", sourceMon, move)
                    Unit
                }
            }
        }
        move("sparklingaria") {
            on("AfterMove") {
                val src = pokemon
                for (p in battle.getAllActive()) {
                    if (p !== src && p.removeVolatile("sparklingaria") && p.status == "brn" && !src.fainted) p.cureStatus()
                }
                Unit
            }
        }
        move("splash") {
            on("Try") {
                if (field.getPseudoWeather("Gravity") != null) {
                    add("cant", pokemon, "move: Gravity", move)
                    null
                } else Unit
            }
            on("TryHit") { add("-nothing"); Unit }
        }
        move("instruct") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val lastMove = t.lastMove ?: return@on false
                if (t.volatiles["dynamax"] != null) return@on false
                val moveIndex = t.moves.indexOf(lastMove.id)
                if (lastMove.flag("failinstruct") || Js.truthy(lastMove.isZ) || Js.truthy(lastMove.isMax) || lastMove.flag("charge") ||
                    lastMove.flag("recharge") || t.volatiles["beakblast"] != null || t.volatiles["focuspunch"] != null ||
                    t.volatiles["shelltrap"] != null || (moveIndex >= 0 && t.moveSlots[moveIndex].pp <= 0)) {
                    return@on false
                }
                add("-singleturn", t, "move: Instruct", "[of] $s")
                val action = Action("move", pokemon = t, moveid = lastMove.id, targetLoc = t.lastMoveTargetLoc ?: 0)
                battle.queue.prioritizeAction(battle.queue.resolveAction(action)[0])
                Unit
            }
        }
        move("watershuriken") {
            callback("basePowerCallback") {
                val p = pokemon
                if (p.species.name == "Greninja-Ash" && p.hasAbility("battlebond") && !p.transformed) move.basePower + 5 else move.basePower
            }
            on("ModifyMove") {
                val p = pokemon
                if (p.species.name == "Greninja-Ash" && p.hasAbility("battlebond") && !p.transformed) (relay as ActiveMove).multihit = listOf(3, 5)
                Unit
            }
        }
        move("mistyterrain") {
            condition {
                terrainDuration(this)
                on("SetStatus") {
                    val t = pokemon
                    if (t.isGrounded() != true || t.isSemiInvulnerable()) return@on Unit
                    val e = sourceEffect
                    if (e != null && (statusOf(e) != null || e.id == "yawn")) add("-activate", t, "move: Misty Terrain")
                    false
                }
                on("TryAddVolatile") {
                    val t = pokemon
                    if (t.isGrounded() != true || t.isSemiInvulnerable()) return@on Unit
                    if ((relay as EffectLike).id == "confusion") {
                        val e = sourceEffect
                        if (e?.effectType == "Move" && !hasSecondaries(e)) add("-activate", t, "move: Misty Terrain")
                        return@on null
                    }
                    Unit
                }
                on("BasePower") {
                    val defender = sourceMon!!
                    if (move.type == "Dragon" && defender.isGrounded() == true && !defender.isSemiInvulnerable()) chainModify(0.5) else Unit
                }
                on("FieldStart") {
                    val e = sourceEffect
                    if (e?.effectType == "Ability") add("-fieldstart", "move: Misty Terrain", "[from] ability: " + e.name, "[of] $source")
                    else add("-fieldstart", "move: Misty Terrain")
                    Unit
                }
                on("FieldEnd") { add("-fieldend", "Misty Terrain"); Unit }
            }
        }
        move("focusenergy") {
            condition {
                on("Start") {
                    val t = pokemon
                    if (t.volatiles["dragoncheer"] != null) return@on false
                    val e = sourceEffect
                    when {
                        e?.id == "zpower" -> add("-start", t, "move: Focus Energy", "[zeffect]")
                        e != null && e.id in listOf("costar", "imposter", "psychup", "transform") -> add("-start", t, "move: Focus Energy", "[silent]")
                        else -> add("-start", t, "move: Focus Energy")
                    }
                    Unit
                }
                on("ModifyCritRatio") { relayInt + 2 }
            }
        }
        move("quash") {
            on("Hit") {
                if (battle.activePerHalf == 1) return@on false
                val action = battle.queue.willMove(pokemon) ?: return@on false
                action.order = 201
                add("-activate", target, "move: Quash")
                Unit
            }
        }
        move("relicsong") {
            on("Hit") {
                val p = sourceMon!!
                if (p.baseSpecies.baseSpecies == "Meloetta" && !p.transformed) move.extra["willChangeForme"] = true
                Unit
            }
            on("AfterMoveSecondarySelf") {
                if (Js.truthy(move.extra["willChangeForme"])) {
                    val p = pokemon
                    val meloettaForme = if (p.species.id == "meloettapirouette") "" else "-Pirouette"
                    p.formeChange("Meloetta$meloettaForme", self, true, "[msg]")
                }
                Unit
            }
        }
        move("noretreat") {
            on("Try") {
                val s = pokemon
                if (s.volatiles["noretreat"] != null) return@on false
                if (s.volatiles["trapped"] != null) move.volatileStatus = null
                Unit
            }
            condition {
                on("Start") { add("-start", pokemon, "move: No Retreat"); Unit }
                on("TrapPokemon") { pokemon.tryTrap(); Unit }
            }
        }
        move("lunardance") {
            on("TryHit") {
                if (battle.canSwitch(pokemon.side) == 0) {
                    battle.attrLastMove("[still]")
                    add("-fail", pokemon)
                    NOT_FAIL
                } else Unit
            }
            condition {
                on("Swap") {
                    val t = pokemon
                    if (!t.fainted && (t.hp < t.maxhp || t.status.isNotEmpty() || t.moveSlots.any { it.pp < it.maxpp })) {
                        t.heal(t.maxhp.toDouble())
                        t.clearStatus()
                        for (slot in t.moveSlots) slot.pp = slot.maxpp
                        add("-heal", t, t.getHealth, "[from] move: Lunar Dance")
                        t.side.removeSlotCondition(t, "lunardance")
                    }
                    Unit
                }
            }
        }
        move("collisioncourse") {
            on("BasePower") { if (sourceMon!!.runEffectiveness(move) > 0) chainModify(intArrayOf(5461, 4096)) else Unit }
        }
        move("healbell") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                add("-activate", s, "move: Heal Bell")
                var success = false
                val allies = t.side.pokemon + (t.side.allySide?.pokemon ?: emptyList())
                for (ally in allies) {
                    if (ally !== s && ally.hasAbility("soundproof")) continue
                    if (ally.cureStatus()) success = true
                }
                success
            }
        }
        move("safeguard") {
            condition {
                callback("durationCallback") {
                    val s = sourceMon
                    if (s?.hasAbility("persistent") == true) {
                        add("-activate", s, "ability: Persistent", "[move] Safeguard")
                        7
                    } else 5
                }
                on("SetStatus") {
                    val e = sourceEffect
                    val s = sourceMon
                    val t = pokemon
                    if (e == null || s == null) return@on Unit
                    if (e.id == "yawn") return@on Unit
                    if (e.effectType == "Move" && infiltrates(e) && !t.isAlly(s)) return@on Unit
                    if (t !== s) {
                        if (e.name == "Synchronize" || (e.effectType == "Move" && !hasSecondaries(e))) add("-activate", t, "move: Safeguard")
                        return@on null
                    }
                    Unit
                }
                on("TryAddVolatile") {
                    val e = sourceEffect
                    val s = sourceMon
                    val t = pokemon
                    if (e == null || s == null) return@on Unit
                    if (e.effectType == "Move" && infiltrates(e) && !t.isAlly(s)) return@on Unit
                    val id = (relay as EffectLike).id
                    if ((id == "confusion" || id == "yawn") && t !== s) {
                        if (e.effectType == "Move" && !hasSecondaries(e)) add("-activate", t, "move: Safeguard")
                        return@on null
                    }
                    Unit
                }
                on("SideStart") {
                    if (sourceMon?.hasAbility("persistent") == true) add("-sidestart", target, "Safeguard", "[persistent]")
                    else add("-sidestart", target, "Safeguard")
                    Unit
                }
                on("SideEnd") { add("-sideend", target, "Safeguard"); Unit }
            }
        }
        itemSteal("thief") { t, s, item ->
            add("-enditem", t, item, "[silent]", "[from] move: Thief", "[of] $s")
            add("-item", s, item, "[from] move: Thief", "[of] $t")
        }
        itemSteal("covet") { t, s, item ->
            add("-item", s, item, "[from] move: Covet", "[of] $t")
        }
        move("spitup") {
            callback("basePowerCallback") {
                val layers = pokemon.volatiles["stockpile"]?.get("layers")
                if (!Js.truthy(layers)) return@callback false
                Js.int(layers) * 100
            }
            on("Try") { pokemon.volatiles["stockpile"] != null }
            on("AfterMove") { pokemon.removeVolatile("stockpile"); Unit }
        }
        move("worryseed") {
            on("TryImmunity") { if (pokemon.ability == "truant" || pokemon.ability == "insomnia") false else Unit }
            on("TryHit") { if (pokemon.getAbility().flag("cantsuppress")) false else Unit }
            on("Hit") {
                val p = pokemon
                val oldAbility = p.setAbility("insomnia")
                if (Js.truthy(oldAbility)) {
                    add("-ability", p, "Insomnia", "[from] move: Worry Seed")
                    if (p.status == "slp") p.cureStatus()
                    return@on Unit
                }
                oldAbility
            }
        }
        move("aquaring") {
            condition {
                on("Start") { add("-start", pokemon, "Aqua Ring"); Unit }
                on("Residual") { heal(pokemon.baseMaxhp / 16.0); Unit }
            }
        }
        move("torment") {
            condition {
                on("Start") {
                    val p = pokemon
                    if (p.volatiles["dynamax"] != null) {
                        p.volatiles.remove("torment")
                        return@on false
                    }
                    if (sourceEffect?.id == "gmaxmeltdown") state.duration = 3
                    add("-start", p, "Torment")
                    Unit
                }
                on("End") { add("-end", pokemon, "Torment"); Unit }
                on("DisableMove") {
                    val lm = pokemon.lastMove
                    if (lm != null && lm.id != "struggle") pokemon.disableMove(lm.id)
                    Unit
                }
            }
        }
        move("uproar") {
            on("TryHit") {
                val t = pokemon
                val activeTeam = t.side.activeTeam()
                val foeActiveTeam = t.side.foe.activeTeam()
                for ((i, allyActive) in activeTeam.withIndex()) {
                    if (allyActive != null && allyActive.status == "slp") allyActive.cureStatus()
                    val foeActive = foeActiveTeam.getOrNull(i)
                    if (foeActive != null && foeActive.status == "slp") foeActive.cureStatus()
                }
                Unit
            }
            condition {
                on("Start") { add("-start", target, "Uproar"); Unit }
                on("Residual") {
                    val t = pokemon
                    if (t.volatiles["throatchop"] != null) {
                        t.removeVolatile("uproar")
                        return@on Unit
                    }
                    if (t.lastMove != null && t.lastMove!!.id == "struggle") t.volatiles.remove("uproar")
                    add("-start", t, "Uproar", "[upkeep]")
                    Unit
                }
                on("End") { add("-end", target, "Uproar"); Unit }
                on("AnySetStatus") {
                    if ((relay as EffectLike).id == "slp") {
                        val p = pokemon
                        if (p === state.target) add("-fail", p, "slp", "[from] Uproar", "[msg]")
                        else add("-fail", p, "slp", "[from] Uproar")
                        return@on null
                    }
                    Unit
                }
            }
        }
        move("terrainpulse") {
            on("ModifyType") {
                val m = relay as ActiveMove
                if (pokemon.isGrounded() != true) return@on Unit
                when (field.terrain) {
                    "electricterrain" -> m.type = "Electric"
                    "grassyterrain" -> m.type = "Grass"
                    "mistyterrain" -> m.type = "Fairy"
                    "psychicterrain" -> m.type = "Psychic"
                }
                Unit
            }
            on("ModifyMove") {
                if (field.terrain.isNotEmpty() && pokemon.isGrounded() == true) (relay as ActiveMove).basePower *= 2
                Unit
            }
        }
        move("present") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                val rand = random(10)
                when {
                    rand < 2 -> {
                        m.heal = intArrayOf(1, 4)
                        m.infiltrates = true
                    }
                    rand < 6 -> m.basePower = 40
                    rand < 9 -> m.basePower = 80
                    else -> m.basePower = 120
                }
                Unit
            }
        }
        move("doodle") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                var success: Any? = false
                if (!t.getAbility().flag("failroleplay")) {
                    for (p in s.alliesAndSelf()) {
                        if (p.ability == t.ability || p.getAbility().flag("cantsuppress")) continue
                        val oldAbility = p.setAbility(t.ability)
                        if (Js.truthy(oldAbility)) {
                            add("-ability", p, t.getAbility().name, "[from] move: Doodle")
                            success = true
                        } else if (!Js.truthy(success) && oldAbility == null) {
                            success = null
                        }
                    }
                }
                if (!Js.truthy(success)) {
                    if (success == false) add("-fail", s)
                    battle.attrLastMove("[still]")
                    return@on NOT_FAIL
                }
                Unit
            }
        }
        move("eeriespell") {
            secondary {
                on("Hit") {
                    val t = pokemon
                    if (t.hp == 0) return@on Unit
                    val last = t.lastMove ?: return@on Unit
                    if (Js.truthy(last.isZ)) return@on Unit
                    var m: EffectLike = last
                    if (Js.truthy(last.isMax) && last.baseMove != null) m = dex.move(last.baseMove!!) ?: last
                    val ppDeducted = t.deductPP(m.id, 3)
                    if (ppDeducted == 0) return@on Unit
                    add("-activate", t, "move: Eerie Spell", m.name, ppDeducted)
                    Unit
                }
            }
        }
        move("conversion") {
            on("Hit") {
                val t = pokemon
                val type = dex.move(t.moveSlots[0].id)?.type ?: return@on false
                if (t.hasType(type) || !t.setType(type)) return@on false
                add("-start", t, "typechange", type)
                Unit
            }
        }
        move("flyingpress") {
            on("Effectiveness") { relayInt + dex.effectiveness("Flying", source as String) }
        }
        move("brine") {
            on("BasePower") {
                val t = sourceMon!!
                if (t.hp * 2 <= t.maxhp) chainModify(2) else Unit
            }
        }
        move("powersplit") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val newatk = (t.storedStats.getValue("atk") + s.storedStats.getValue("atk")) / 2
                t.storedStats["atk"] = newatk
                s.storedStats["atk"] = newatk
                val newspa = (t.storedStats.getValue("spa") + s.storedStats.getValue("spa")) / 2
                t.storedStats["spa"] = newspa
                s.storedStats["spa"] = newspa
                add("-activate", s, "move: Power Split", "[of] $t")
                Unit
            }
        }
        move("round") {
            callback("basePowerCallback") { if (move.sourceEffect == "round") move.basePower * 2 else move.basePower }
            on("Try") {
                for (action in battle.queue.list.toList()) {
                    if (action.pokemon == null || action.move == null || action.maxMove != null) continue
                    if (action.move!!.id == "round") {
                        battle.queue.prioritizeAction(action, move)
                        return@on Unit
                    }
                }
                Unit
            }
        }
        move("magicpowder") {
            on("Hit") {
                val t = pokemon
                if (t.getTypes().joinToString(",") == "Psychic" || !t.setType("Psychic")) return@on false
                add("-start", t, "typechange", "Psychic")
                Unit
            }
        }
        move("guardswap") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val targetBoosts = LinkedHashMap<String, Int>()
                val sourceBoosts = LinkedHashMap<String, Int>()
                for (stat in listOf("def", "spd")) {
                    targetBoosts[stat] = t.boosts.getValue(stat)
                    sourceBoosts[stat] = s.boosts.getValue(stat)
                }
                s.setBoost(targetBoosts)
                t.setBoost(sourceBoosts)
                add("-swapboost", s, t, "def, spd", "[from] move: Guard Swap")
                Unit
            }
        }
        move("attract") {
            on("TryImmunity") {
                val t = pokemon
                val s = sourceMon!!
                (t.gender == "M" && s.gender == "F") || (t.gender == "F" && s.gender == "M")
            }
            condition {
                on("Start") {
                    val p = pokemon
                    val s = sourceMon!!
                    if (!(p.gender == "M" && s.gender == "F") && !(p.gender == "F" && s.gender == "M")) return@on false
                    if (!Js.truthy(battle.runEvent("Attract", p, s))) return@on false
                    when (sourceEffect?.name) {
                        "Cute Charm" -> add("-start", p, "Attract", "[from] ability: Cute Charm", "[of] $s")
                        "Destiny Knot" -> add("-start", p, "Attract", "[from] item: Destiny Knot", "[of] $s")
                        else -> add("-start", p, "Attract")
                    }
                    Unit
                }
                on("Update") {
                    val p = pokemon
                    val s = state.source
                    if (s != null && !s.isActive && p.volatiles["attract"] != null) p.removeVolatile("attract")
                    Unit
                }
                on("BeforeMove") {
                    add("-activate", pokemon, "move: Attract", "[of] ${state.source}")
                    if (randomChance(1, 2)) {
                        add("cant", pokemon, "Attract")
                        false
                    } else Unit
                }
                on("End") { add("-end", pokemon, "Attract", "[silent]"); Unit }
            }
        }
        move("reflecttype") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                if (s.species.num == 493 || s.species.num == 773) return@on false
                if (s.terastallized != null) return@on false
                val oldApparentType = s.apparentType
                var newBaseTypes = t.getTypes(true).filter { it != "???" }
                if (newBaseTypes.isEmpty()) {
                    if (t.addedType.isNotEmpty()) newBaseTypes = listOf("Normal") else return@on false
                }
                add("-start", s, "typechange", "[from] move: Reflect Type", "[of] $t")
                s.setType(newBaseTypes)
                s.addedType = t.addedType
                s.knownType = t.isAlly(s) && t.knownType
                if (!s.knownType) s.apparentType = oldApparentType
                Unit
            }
        }
        move("celebrate") {
            on("TryHit") { add("-activate", target, "move: Celebrate"); Unit }
        }
        move("incinerate") {
            on("Hit") {
                val p = pokemon
                val item = p.getItem()
                if ((item.bool("isBerry") || item.bool("isGem")) && Js.truthy(p.takeItem(sourceMon))) {
                    add("-enditem", p, item.name, "[from] move: Incinerate")
                }
                Unit
            }
        }
        move("gmaxcuddle") {
            self {
                on("Hit") {
                    for (p in pokemon.foes()) p.addVolatile("attract")
                    Unit
                }
            }
        }
        move("gmaxfoamburst") {
            self {
                on("Hit") {
                    for (p in pokemon.foes()) battle.boost(mapOf("spe" to -2), p)
                    Unit
                }
            }
        }
        gmaxStatus("gmaxmalodor", "psn")
        gmaxStatus("gmaxvoltcrash", "par")
        move("gmaxstunshock") {
            self {
                on("Hit") {
                    val src = pokemon
                    for (p in src.foes()) {
                        if (random(2) == 0) p.trySetStatus("par", src) else p.trySetStatus("psn", src)
                    }
                    Unit
                }
            }
        }
        move("gmaxterror") {
            self {
                on("Hit") {
                    val src = pokemon
                    for (p in src.foes()) p.addVolatile("trapped", src, null, "trapper")
                    Unit
                }
            }
        }
        move("gmaxsnooze") {
            on("Hit") { snooze(pokemon); Unit }
            on("AfterSubDamage") { snooze(pokemon); Unit }
        }
        move("gravapple") {
            on("BasePower") { if (field.getPseudoWeather("gravity") != null) chainModify(1.5) else Unit }
        }
        move("powershift") {
            condition {
                on("Start") {
                    add("-start", pokemon, "Power Shift")
                    swapAtkDef(pokemon)
                    Unit
                }
                on("Copy") { swapAtkDef(pokemon); Unit }
                on("End") {
                    add("-end", pokemon, "Power Shift")
                    swapAtkDef(pokemon)
                    Unit
                }
                on("Restart") { pokemon.removeVolatile("Power Shift"); Unit }
            }
        }
        move("teatime") {
            on("HitField") {
                val src = sourceMon!!
                val m = move
                val targets = ArrayList<Pokemon>()
                for (p in battle.getAllActive()) {
                    if (battle.runEvent("Invulnerability", p, src, m) == false) {
                        add("-miss", src, p)
                    } else if (Js.truthy(battle.runEvent("TryHit", p, src, m)) && p.getItem().bool("isBerry")) {
                        targets.add(p)
                    }
                }
                add("-fieldactivate", "move: Teatime")
                if (targets.isEmpty()) {
                    add("-fail", src, "move: Teatime")
                    battle.attrLastMove("[still]")
                    return@on NOT_FAIL
                }
                for (p in targets) p.eatItem(true)
                Unit
            }
        }
    }

    // region Helpers

    private val MINIMIZE_MOVES = listOf("stomp", "steamroller", "bodyslam", "flyingpress", "dragonrush", "heatcrash", "heavyslam",
        "maliciousmoonsault")

    private fun infiltrates(effect: EffectLike): Boolean =
        if (effect is ActiveMove) effect.infiltrates else Js.truthy(effect.data("infiltrates"))

    private fun hasSecondaries(effect: EffectLike?): Boolean = when (effect) {
        null -> false
        is ActiveMove -> effect.secondaries != null
        else -> effect.data("secondaries") != null
    }

    private fun statusOf(effect: EffectLike): String? = when (effect) {
        is ActiveMove -> effect.status
        is Effect -> effect.string("status")
        else -> effect.data("status") as? String
    }

    private fun swapAtkDef(p: Pokemon) {
        val newatk = p.storedStats.getValue("def")
        val newdef = p.storedStats.getValue("atk")
        p.storedStats["atk"] = newatk
        p.storedStats["def"] = newdef
    }

    private fun dropLockedMove(source: Pokemon) {
        if (source.getVolatile("lockedmove") != null && source.volatiles["lockedmove"]?.duration == 2) source.volatiles.remove("lockedmove")
    }

    private fun snooze(target: Pokemon) {
        if (target.status.isNotEmpty() || !target.runStatusImmunity("slp")) return
        if (target.battle.random(2) == 0) return
        target.addVolatile("yawn")
    }


    private fun terrainDuration(hooks: jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks) = hooks.callback("durationCallback") {
        if ((target as? Pokemon)?.hasItem("terrainextender") == true) 8 else 5
    }

    private fun rapidSpin(call: HookCall) = with(call) {
        val p = sourceMon!!
        if (move.hasSheerForce) return@with
        if (p.hp != 0 && p.removeVolatile("leechseed")) add("-end", p, "Leech Seed", "[from] move: Rapid Spin", "[of] $p")
        for (condition in HAZARDS) {
            if (p.hp != 0 && p.side.removeSideCondition(condition)) {
                add("-sideend", p.side, dex.condition(condition).name, "[from] move: Rapid Spin", "[of] $p")
            }
        }
        if (p.hp != 0 && p.volatiles["partiallytrapped"] != null) p.removeVolatile("partiallytrapped")
    }

    private fun HookRegistrar.screen(id: String, name: String, category: String) = move(id) {
        condition {
            callback("durationCallback") { if (sourceMon?.hasItem("lightclay") == true) 8 else 5 }
            on("AnyModifyDamage") {
                val src = pokemon
                val t = sourceMon ?: return@on Unit
                val m = move
                if (t !== src && (state.target as Side).hasAlly(t) && m.category == category) {
                    if (!t.getMoveHitData(m).crit && !m.infiltrates) {
                        return@on if (battle.activePerHalf > 1) chainModify(intArrayOf(2732, 4096)) else chainModify(0.5)
                    }
                }
                Unit
            }
            on("SideStart") { add("-sidestart", target, name); Unit }
            on("SideEnd") { add("-sideend", target, name); Unit }
        }
    }

    private fun HookRegistrar.screenBreaker(id: String) = move(id) {
        on("TryHit") {
            val side = pokemon.side
            side.removeSideCondition("reflect")
            side.removeSideCondition("lightscreen")
            side.removeSideCondition("auroraveil")
            Unit
        }
    }

    private fun HookRegistrar.simpleCharge(id: String) = move(id) {
        on("TryMove") {
            val attacker = pokemon
            val defender = sourceMon
            val m = move
            if (attacker.removeVolatile(m.id)) return@on Unit
            add("-prepare", attacker, m.name)
            if (!Js.truthy(battle.runEvent("ChargeMove", attacker, defender, m))) return@on Unit
            attacker.addVolatile("twoturnmove", defender)
            null
        }
    }

    private fun HookRegistrar.gmaxResidual(id: String, name: String, immuneType: String) = move(id) {
        self {
            on("Hit") {
                for (side in pokemon.side.foeSidesWithConditions()) side.addSideCondition(id)
                Unit
            }
        }
        condition {
            on("SideStart") { add("-sidestart", target, name); Unit }
            on("Residual") {
                val t = pokemon
                if (!t.hasType(immuneType)) damage(t.baseMaxhp / 6.0, t)
                Unit
            }
            on("SideEnd") { add("-sideend", target, name); Unit }
        }
    }

    private fun HookRegistrar.gmaxStatus(id: String, status: String) = move(id) {
        self {
            on("Hit") {
                val src = pokemon
                for (p in src.foes()) p.trySetStatus(status, src)
                Unit
            }
        }
    }

    private fun HookRegistrar.itemSteal(id: String, announce: HookCall.(Pokemon, Pokemon, Effect) -> Unit) = move(id) {
        on("AfterHit") {
            val t = pokemon
            val s = sourceMon!!
            if (s.item.isNotEmpty() || s.volatiles["gem"] != null) return@on Unit
            val yourItem = t.takeItem(s) as? Effect ?: return@on Unit
            if (!Js.truthy(battle.singleEvent("TakeItem", yourItem, t.itemState, s, t, move, yourItem)) || !s.setItem(yourItem.id)) {
                t.item = yourItem.id
                return@on Unit
            }
            announce(t, s, yourItem)
            Unit
        }
    }

    // endregion
}
