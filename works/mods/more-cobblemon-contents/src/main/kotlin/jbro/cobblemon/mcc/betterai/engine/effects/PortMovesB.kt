package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.MoveSlot
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove

/** A second batch of moves with code in `data/moves.js`. */
object PortMovesB : HookSet() {
    /** `battle.getCategory(move)`: the dex move's category, not the active move's. */
    internal fun Battle.categoryOf(move: EffectLike): String = dex.move(move.id)?.category ?: "Physical"

    /** `battle.getAtSlot(slot)`. */
    internal fun Battle.atSlot(slot: String?): Pokemon? {
        if (slot.isNullOrEmpty()) return null
        val side = sides[slot[1].code - 49]
        val position = slot[2].code - 97
        val positionOffset = (side.n / 2) * side.active.size
        return side.active.getOrNull(position - positionOffset)
    }

    override fun HookRegistrar.define() {
        batch2()
        batch3()
        batch4()
        move("judgment") {
            on("ModifyType") {
                val m = relay as ActiveMove
                val p = pokemon
                if (p.ignoringItem()) return@on Unit
                val item = p.getItem()
                if (item.id.isNotEmpty() && Js.truthy(item.data("onPlate")) && !Js.truthy(item.data("zMove"))) {
                    m.type = item.data("onPlate") as String
                }
                Unit
            }
        }
        move("toxicspikes") {
            condition {
                on("SideStart") {
                    add("-sidestart", target, "move: Toxic Spikes")
                    state["layers"] = 1
                    Unit
                }
                on("SideRestart") {
                    if (state.int("layers") >= 2) return@on false
                    add("-sidestart", target, "move: Toxic Spikes")
                    state["layers"] = state.int("layers") + 1
                    Unit
                }
                on("EntryHazard") {
                    val p = pokemon
                    if (!Js.truthy(p.isGrounded())) return@on Unit
                    if (p.hasType("Poison")) {
                        add("-sideend", p.side, "move: Toxic Spikes", "[of] $p")
                        p.side.removeSideCondition("toxicspikes")
                    } else if (p.hasType("Steel") || p.hasItem("heavydutyboots")) {
                        return@on Unit
                    } else if (state.int("layers") >= 2) {
                        p.trySetStatus("tox", p.side.foe.active[0])
                    } else {
                        p.trySetStatus("psn", p.side.foe.active[0])
                    }
                    Unit
                }
            }
        }
        redirector("followme", "move: Follow Me", powder = false)
        redirector("ragepowder", "move: Rage Powder", powder = true)
        move("hurricane") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                when (sourceMon?.effectiveWeather()) {
                    "raindance", "primordialsea" -> m.accuracy = true
                    "sunnyday", "desolateland" -> m.accuracy = 50
                }
                Unit
            }
        }
        move("reflect") {
            condition {
                callback("durationCallback") { if (sourceMon?.hasItem("lightclay") == true) 8 else 5 }
                on("AnyModifyDamage") {
                    val src = targetMon
                    val tgt = sourceMon!!
                    val m = move
                    if (tgt !== src && (state.target as Side).hasAlly(tgt) && battle.categoryOf(m) == "Physical") {
                        if (!tgt.getMoveHitData(m).crit && !m.infiltrates) {
                            if (battle.activePerHalf > 1) return@on chainModify(intArrayOf(2732, 4096))
                            return@on chainModify(0.5)
                        }
                    }
                    Unit
                }
                on("SideStart") { add("-sidestart", target, "Reflect"); Unit }
                on("SideEnd") { add("-sideend", target, "Reflect"); Unit }
            }
        }
        move("curse") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                val src = pokemon
                val tgt = sourceMon
                val isGhost = src.hasType("Ghost")
                val isGhostTera = src.teraType == "Ghost" || (src.hasType("Ghost") && src.teraType == "Stellar")
                if (src.terastallized == null) {
                    if (!isGhost) m.target = m.nonGhostTarget
                    else if (src.isAlly(tgt)) m.target = "randomNormal"
                }
                if (src.terastallized != null) {
                    if (!isGhostTera) m.target = m.nonGhostTarget
                    else if (src.isAlly(tgt)) m.target = "randomNormal"
                }
                Unit
            }
            on("TryHit") {
                val tgt = pokemon
                val src = sourceMon!!
                val m = move
                val isGhost = src.hasType("Ghost")
                val isGhostTera = src.teraType == "Ghost" || (src.hasType("Ghost") && src.teraType == "Stellar")
                if (src.terastallized == null) {
                    if (!isGhost) {
                        m.volatileStatus = null
                        m.extra[DELETED_ON_HIT] = true
                        m.self = LiteralHit(hitBoosts = linkedMapOf("spe" to -1, "atk" to 1, "def" to 1))
                    } else if (m.volatileStatus != null && tgt.volatiles["curse"] != null) {
                        return@on false
                    }
                }
                if (src.terastallized != null) {
                    if (!isGhostTera) {
                        m.volatileStatus = null
                        m.extra[DELETED_ON_HIT] = true
                        m.self = LiteralHit(hitBoosts = linkedMapOf("spe" to -1, "atk" to 1, "def" to 1))
                    } else if (m.volatileStatus != null && tgt.volatiles["curse"] != null) {
                        return@on false
                    }
                }
                Unit
            }
            on("Hit") {
                if (activeMove?.extra?.get(DELETED_ON_HIT) == true) return@on Unit
                val src = sourceMon!!
                battle.directDamage(src.maxhp / 2.0, src, src)
                Unit
            }
            condition {
                on("Start") { add("-start", target, "Curse", "[of] $source"); Unit }
                on("Residual") { damage(pokemon.baseMaxhp / 4.0); Unit }
            }
        }
        move("healblock") {
            condition {
                callback("durationCallback") {
                    val src = sourceMon
                    if (sourceEffect?.name == "Psychic Noise") return@callback 2
                    if (src?.hasAbility("persistent") == true) {
                        add("-activate", src, "ability: Persistent", "[move] Heal Block")
                        return@callback 7
                    }
                    5
                }
                on("Start") {
                    add("-start", target, "move: Heal Block")
                    sourceMon!!.moveThisTurnResult = true
                    Unit
                }
                on("DisableMove") {
                    val p = pokemon
                    for (slot in p.moveSlots) {
                        if (dex.move(slot.id)?.flag("heal") == true) p.disableMove(slot.id)
                    }
                    Unit
                }
                on("BeforeMove") {
                    val m = move
                    if (m.flag("heal") && !Js.truthy(m.isZ) && !Js.truthy(m.isMax)) {
                        add("cant", pokemon, "move: Heal Block", m)
                        false
                    } else Unit
                }
                on("ModifyMove") {
                    val m = relay as ActiveMove
                    if (m.flag("heal") && !Js.truthy(m.isZ) && !Js.truthy(m.isMax)) {
                        add("cant", pokemon, "move: Heal Block", m)
                        false
                    } else Unit
                }
                on("End") { add("-end", target, "move: Heal Block"); Unit }
                on("TryHeal") {
                    if (sourceEffect?.id == "zpower" || Js.truthy(state["isZ"])) relay else false
                }
                on("Restart") {
                    add("-fail", target, "move: Heal Block")
                    val src = sourceMon!!
                    if (!Js.truthy(src.moveThisTurnResult)) src.moveThisTurnResult = false
                    Unit
                }
            }
        }
        move("heavyslam") {
            callback("basePowerCallback") { weightPower(pokemon, sourceMon!!) }
            on("TryHit") { dynamaxFail(this.pokemon, sourceMon!!) }
        }
        move("pollenpuff") {
            on("TryHit") {
                if (sourceMon!!.isAlly(pokemon)) {
                    move.basePower = 0
                    move.infiltrates = true
                }
                Unit
            }
            on("TryMove") {
                val src = pokemon
                if (src.isAlly(sourceMon) && src.volatiles["healblock"] != null) {
                    battle.attrLastMove("[still]")
                    add("cant", src, "move: Heal Block", move)
                    return@on false
                }
                Unit
            }
            on("Hit") {
                val tgt = pokemon
                val src = sourceMon!!
                if (src.isAlly(tgt)) {
                    if (!Js.truthy(heal(Math.floor(tgt.baseMaxhp * 0.5)))) {
                        if (tgt.volatiles["healblock"] != null && tgt.hp != tgt.maxhp) {
                            battle.attrLastMove("[still]")
                            add("cant", src, "move: Heal Block", move)
                        } else {
                            add("-immune", tgt)
                        }
                        return@on BattleActions.NOT_FAIL
                    }
                }
                Unit
            }
        }
        move("freezedry") {
            on("Effectiveness") { if (source == "Water") 1 else Unit }
        }
        move("partingshot") {
            on("Hit") {
                val tgt = pokemon
                val success = battle.boost(linkedMapOf("atk" to -1, "spa" to -1), tgt, sourceMon)
                if (!Js.truthy(success) && !tgt.hasAbility("mirrorarmor")) move.selfSwitch = null
                Unit
            }
        }
        counterMove("mirrorcoat", "Special")
        move("facade") {
            on("BasePower") {
                val p = pokemon
                if (p.status.isNotEmpty() && p.status != "slp") chainModify(2) else Unit
            }
        }
        move("superfang") {
            callback("damageCallback") { Js.clampIntRange(sourceMon!!.getUndynamaxedHP() / 2.0, 1) }
        }
        move("wish") {
            condition {
                on("Start") { state["hp"] = Js.number(sourceMon!!.maxhp / 2.0); Unit }
                on("End") {
                    val tgt = targetMon
                    if (tgt != null && !tgt.fainted) {
                        val dealt = heal(Js.num(state["hp"]), tgt, tgt)
                        if (Js.truthy(dealt)) {
                            add("-heal", tgt, tgt.getHealth, "[from] move: Wish", "[wisher] " + state.source!!.name)
                        }
                    }
                    Unit
                }
            }
        }
        move("skillswap") {
            on("TryHit") {
                val tgt = pokemon
                val src = sourceMon!!
                val targetAbility = tgt.getAbility()
                val sourceAbility = src.getAbility()
                if (sourceAbility.flag("failskillswap") || targetAbility.flag("failskillswap") || tgt.volatiles["dynamax"] != null) {
                    return@on false
                }
                val sourceCanBeSet = battle.runEvent("SetAbility", src, src, self, targetAbility)
                if (!Js.truthy(sourceCanBeSet)) return@on sourceCanBeSet
                val targetCanBeSet = battle.runEvent("SetAbility", tgt, src, self, sourceAbility)
                if (!Js.truthy(targetCanBeSet)) return@on targetCanBeSet
                Unit
            }
            on("Hit") {
                val tgt = pokemon
                val src = sourceMon!!
                val targetAbility = tgt.getAbility()
                val sourceAbility = src.getAbility()
                if (tgt.isAlly(src)) add("-activate", src, "move: Skill Swap", "", "", "[of] $tgt")
                else add("-activate", src, "move: Skill Swap", targetAbility, sourceAbility, "[of] $tgt")
                battle.singleEvent("End", sourceAbility, src.abilityState, src)
                battle.singleEvent("End", targetAbility, tgt.abilityState, tgt)
                src.ability = targetAbility.id
                tgt.ability = sourceAbility.id
                src.abilityState = EffectState(Js.toID(src.ability)).also { it.target = src }
                tgt.abilityState = EffectState(Js.toID(tgt.ability)).also { it.target = tgt }
                if (!tgt.isAlly(src)) tgt.volatileStaleness = "external"
                battle.singleEvent("Start", targetAbility, src.abilityState, src)
                battle.singleEvent("Start", sourceAbility, tgt.abilityState, tgt)
                Unit
            }
        }
        move("stompingtantrum") {
            callback("basePowerCallback") { if (pokemon.moveLastTurnResult == false) move.basePower * 2 else move.basePower }
        }
        move("haze") {
            on("HitField") {
                add("-clearallboost")
                for (p in battle.getAllActive()) p.clearBoosts()
                Unit
            }
        }
        move("strengthsap") {
            on("Hit") {
                val tgt = pokemon
                val src = sourceMon!!
                if (tgt.boosts["atk"] == -6) return@on false
                val atk = tgt.getStat("atk", false, true)
                val success = battle.boost(linkedMapOf("atk" to -1), tgt, src, null, false, true)
                Js.truthy(heal(atk, src, tgt)) || Js.truthy(success)
            }
        }
    }

    const val DELETED_ON_HIT = "deletedOnHit"

    /** Heavy Slam and Heat Crash. */
    fun weightPower(pokemon: Pokemon, target: Pokemon): Int {
        val targetWeight = target.getWeight()
        val pokemonWeight = pokemon.getWeight()
        return when {
            pokemonWeight >= targetWeight * 5 -> 120
            pokemonWeight >= targetWeight * 4 -> 100
            pokemonWeight >= targetWeight * 3 -> 80
            pokemonWeight >= targetWeight * 2 -> 60
            else -> 40
        }
    }

    /** `onTryHit` of weight-based moves: they fail against a Dynamaxed target. */
    fun dynamaxFail(target: Pokemon, source: Pokemon): Any? {
        if (target.volatiles["dynamax"] != null) {
            target.battle.add("-fail", source, "Dynamax")
            target.battle.attrLastMove("[still]")
            return null
        }
        return Unit
    }

    /** Follow Me and Rage Powder. */
    private fun HookRegistrar.redirector(id: String, label: String, powder: Boolean) = move(id) {
        on("Try") { battle.activePerHalf > 1 }
        condition {
            on("Start") {
                if (!powder && (effect as? EffectLike)?.id == "zpower") add("-singleturn", target, label, "[zeffect]")
                else add("-singleturn", target, label)
                Unit
            }
            on("FoeRedirectTarget") {
                val user = state.target as Pokemon
                val src = pokemon
                val m = move
                if (powder) {
                    if (user.isSkyDropped()) return@on Unit
                    if (src.runStatusImmunity("powder") && battle.validTarget(user, src, m.target)) {
                        if (m.smartTarget == true) m.smartTarget = false
                        return@on user
                    }
                } else if (!user.isSkyDropped() && battle.validTarget(user, src, m.target)) {
                    if (m.smartTarget == true) m.smartTarget = false
                    return@on user
                }
                Unit
            }
        }
    }

    /** Mirror Coat and Counter: return double the last damage of one category. */
    internal fun HookRegistrar.counterMove(id: String, category: String) = move(id) {
        callback("damageCallback") {
            val v = pokemon.volatiles[id] ?: return@callback 0
            val d = v["damage"]
            if (Js.truthy(d)) d else 1
        }
        callback("beforeTurnCallback") { pokemon.addVolatile(id); Unit }
        on("Try") {
            val v = pokemon.volatiles[id] ?: return@on false
            if (v.has("slot") && v["slot"] == null) return@on false
            Unit
        }
        condition {
            on("Start") {
                state["slot"] = null
                state["damage"] = 0
                Unit
            }
            on("RedirectTarget") {
                if (move.id != id) return@on Unit
                if (pokemon !== state.target || !Js.truthy(state["slot"])) return@on Unit
                battle.atSlot(state["slot"] as String)
            }
            on("DamagingHit") {
                val tgt = pokemon
                val src = sourceMon!!
                if (!src.isAlly(tgt) && battle.categoryOf(move) == category) {
                    state["slot"] = src.getSlot()
                    state["damage"] = 2 * relayInt
                }
                Unit
            }
        }
    }
    private fun HookRegistrar.batch2() {
        contactProtect("spikyshield", statusMove = false) { t, src -> t.battle.damage(src.baseMaxhp / 8.0, src, t) }
        contactProtect("burningbulwark", statusMove = true) { t, src -> src.trySetStatus("brn", t) }
        move("firstimpression") {
            on("Try") {
                if (pokemon.activeMoveActions > 1) {
                    hint("First Impression only works on your first turn out.")
                    false
                } else Unit
            }
        }
        move("lastrespects") {
            callback("basePowerCallback") { 50 + 50 * pokemon.side.totalFainted }
        }
        move("bellydrum") {
            on("Hit") {
                val t = pokemon
                if (t.hp <= t.maxhp / 2.0 || (t.boosts["atk"] ?: 0) >= 6 || t.maxhp == 1) return@on false
                battle.directDamage(t.maxhp / 2.0)
                boost(linkedMapOf("atk" to 12), t)
                Unit
            }
        }
        move("morningsun") {
            on("Hit") {
                val p = pokemon
                val factor = when (p.effectiveWeather()) {
                    "sunnyday", "desolateland" -> 0.667
                    "raindance", "primordialsea", "sandstorm", "hail", "snow" -> 0.25
                    else -> 0.5
                }
                healOrFail(this, p, modify(p.maxhp, factor))
            }
        }
        move("shoreup") {
            on("Hit") {
                val p = pokemon
                val factor = if (field.isWeather("sandstorm")) 0.667 else 0.5
                healOrFail(this, p, modify(p.maxhp, factor))
            }
        }
        move("floralhealing") {
            on("Hit") {
                val t = pokemon
                val success = if (field.isTerrain("grassyterrain")) Js.truthy(heal(modify(t.baseMaxhp, 0.667)))
                else Js.truthy(heal(Math.ceil(t.baseMaxhp * 0.5)))
                if (success && !t.isAlly(sourceMon)) t.staleness = "external"
                if (!success) {
                    add("-fail", t, "heal")
                    return@on BattleActions.NOT_FAIL
                }
                success
            }
        }
        move("avalanche") {
            callback("basePowerCallback") {
                val t = sourceMon
                val damagedByTarget = pokemon.attackedBy.any { it.source === t && it.damage > 0 && it.thisTurn }
                if (damagedByTarget) move.basePower * 2 else move.basePower
            }
        }
        move("reversal") {
            callback("basePowerCallback") {
                val p = pokemon
                val ratio = maxOf(Math.floor(p.hp * 48.0 / p.maxhp).toInt(), 1)
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
        move("steelbeam") {
            on("AfterMove") {
                val p = pokemon
                val m = move
                if (m.mindBlownRecoil && m.multihit == null) {
                    val hpBeforeRecoil = p.hp
                    battle.damage(Math.round(p.maxhp / 2.0).toInt(), p, p, dex.condition("Steel Beam"), true)
                    if (p.hp <= p.maxhp / 2.0 && hpBeforeRecoil > p.maxhp / 2.0) battle.runEvent("EmergencyExit", p, p)
                }
                Unit
            }
        }
        move("copycat") {
            on("Hit") {
                val p = pokemon
                var m: EffectLike = battle.lastMove ?: return@on Unit
                val last = m as ActiveMove
                if (Js.truthy(last.isMax) && last.baseMove != null) m = dex.move(last.baseMove!!)!!
                val isZ = if (m is ActiveMove) m.isZ else m.data("isZ")
                val isMax = if (m is ActiveMove) m.isMax else m.data("isMax")
                if (m.flag("failcopycat") || Js.truthy(isZ) || Js.truthy(isMax)) return@on false
                useMoveUndefinedTarget(battle, m.id, p)
                Unit
            }
        }
        move("revelationdance") {
            on("ModifyType") {
                val m = relay as ActiveMove
                val p = pokemon
                var type = p.getTypes()[0]
                if (type == "Bird") type = "???"
                if (type == "Stellar") type = p.getTypes(false, true)[0]
                m.type = type
                Unit
            }
        }
        move("fusionflare") {
            on("BasePower") { if (battle.lastSuccessfulMoveThisTurn == "fusionbolt") chainModify(2) else Unit }
        }
        move("metalburst") {
            callback("damageCallback") {
                val last = pokemon.getLastDamagedBy(true) ?: return@callback 0
                val d = last.damage * 1.5
                if (d != 0.0) Js.number(d) else 1
            }
            on("Try") {
                val last = pokemon.getLastDamagedBy(true)
                if (last == null || !last.thisTurn) false else Unit
            }
            on("ModifyTarget") {
                @Suppress("UNCHECKED_CAST")
                val relayMap = relay as MutableMap<String, Any?>
                val last = pokemon.getLastDamagedBy(true)
                if (last != null) relayMap["target"] = battle.atSlot(last.slot)
                Unit
            }
        }
        move("solarblade") {
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
        move("ruination") {
            callback("damageCallback") { Js.clampIntRange(Math.floor(sourceMon!!.getUndynamaxedHP() / 2.0), 1) }
        }
        move("dragoncheer") {
            condition {
                on("Start") {
                    val t = pokemon
                    if (t.volatiles["focusenergy"] != null) return@on false
                    val e = effect as? EffectLike
                    if (e != null && e.id in listOf("costar", "imposter", "psychup", "transform")) add("-start", t, "move: Dragon Cheer", "[silent]")
                    else add("-start", t, "move: Dragon Cheer")
                    state["hasDragonType"] = t.hasType("Dragon")
                    Unit
                }
                on("ModifyCritRatio") { relayInt + (if (Js.truthy(state["hasDragonType"])) 2 else 1) }
            }
        }
        move("stoneaxe") {
            on("AfterHit") { stoneAxe(this, sourceMon!!); Unit }
            on("AfterSubDamage") { stoneAxe(this, sourceMon!!); Unit }
        }
        move("terastarstorm") {
            on("ModifyType") {
                val m = relay as ActiveMove
                val p = pokemon
                if (p.species.name == "Terapagos-Stellar") {
                    m.type = "Stellar"
                    if (p.terastallized != null && p.getStat("atk", false, true) > p.getStat("spa", false, true)) m.category = "Physical"
                }
                Unit
            }
            on("ModifyMove") {
                if (pokemon.species.name == "Terapagos-Stellar") (relay as ActiveMove).target = "allAdjacentFoes"
                Unit
            }
        }
        move("thunderclap") {
            on("Try") {
                val t = sourceMon!!
                val action = battle.queue.willMove(t)
                val m = if (action?.choice == "move") action.move else null
                if (m == null || (m.category == "Status" && m.id != "mefirst") || t.volatiles["mustrecharge"] != null) false else Unit
            }
        }
        move("electrodrift") {
            on("BasePower") { if (sourceMon!!.runEffectiveness(move) > 0) chainModify(intArrayOf(5461, 4096)) else Unit }
        }
    }

    /** Morning Sun, Shore Up: heal a share of max HP or fail loudly. */
    private fun healOrFail(call: HookCall, p: Pokemon, amount: Int): Any? = with(call) {
        val success = Js.truthy(heal(amount))
        if (!success) {
            add("-fail", p, "heal")
            return@with BattleActions.NOT_FAIL
        }
        success
    }

    private fun stoneAxe(call: HookCall, source: Pokemon) = with(call) {
        if (!move.hasSheerForce && source.hp != 0) {
            for (side in source.side.foeSidesWithConditions()) side.addSideCondition("stealthrock")
        }
    }

    /** `this.actions.useMove(id, pokemon)` with no target option: the target is left undefined and picked at random. */
    fun useMoveUndefinedTarget(battle: Battle, id: String, pokemon: Pokemon): Any? =
        battle.actions.useMove(battle.dex.activeMove(id), pokemon, null, targetUndefined = true)

    /** Spiky Shield and Burning Bulwark: Protect with a contact punishment. */
    private fun HookRegistrar.contactProtect(id: String, statusMove: Boolean, punish: (Pokemon, Pokemon) -> Unit) = move(id) {
        on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
        on("Hit") { pokemon.addVolatile("stall"); Unit }
        condition {
            on("Start") { add("-singleturn", target, "move: Protect"); Unit }
            on("TryHit") {
                val t = pokemon
                val src = sourceMon!!
                val m = move
                if (!m.flag("protect") || (statusMove && m.category == "Status")) {
                    if (m.id in listOf("gmaxoneblow", "gmaxrapidflow")) return@on Unit
                    if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) t.getMoveHitData(m).zBrokeProtect = true
                    return@on Unit
                }
                if (m.smartTarget == true) m.smartTarget = false else add("-activate", t, "move: Protect")
                val lockedmove = src.getVolatile("lockedmove")
                if (lockedmove != null && src.volatiles["lockedmove"]?.duration == 2) src.volatiles.remove("lockedmove")
                if (battle.checkMoveMakesContact(m, src, t)) punish(t, src)
                BattleActions.NOT_FAIL
            }
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                if (move.isZOrMaxPowered && battle.checkMoveMakesContact(move, src, t)) punish(t, src)
                Unit
            }
        }
    }
    private fun HookRegistrar.batch3() {
        chargeMove("dig") {
            on("Immunity") { if (relay == "sandstorm" || relay == "hail") false else Unit }
            on("Invulnerability") { if (move.id in listOf("earthquake", "magnitude")) Unit else false }
            on("SourceModifyDamage") { if (move.id == "earthquake" || move.id == "magnitude") chainModify(2) else Unit }
        }
        chargeMove("fly") {
            on("Invulnerability") {
                if (move.id in listOf("gust", "twister", "skyuppercut", "thunder", "hurricane", "smackdown", "thousandarrows")) Unit else false
            }
            on("SourceModifyDamage") { if (move.id == "gust" || move.id == "twister") chainModify(2) else Unit }
        }
        move("dragonenergy") {
            callback("basePowerCallback") { Js.number(move.basePower.toDouble() * pokemon.hp / pokemon.maxhp) }
        }
        move("mortalspin") {
            on("AfterHit") { mortalSpin(this, sourceMon!!); Unit }
            on("AfterSubDamage") { mortalSpin(this, sourceMon!!); Unit }
        }
        move("wildboltstorm") {
            on("ModifyMove") {
                val t = sourceMon
                if (t != null && t.effectiveWeather() in listOf("raindance", "primordialsea")) (relay as ActiveMove).accuracy = true
                Unit
            }
        }
        move("ficklebeam") {
            on("BasePower") {
                if (randomChance(3, 10)) {
                    battle.attrLastMove("[anim] Fickle Beam All Out")
                    add("-activate", pokemon, "move: Fickle Beam")
                    return@on chainModify(2)
                }
                Unit
            }
        }
        move("electricterrain") {
            condition {
                callback("durationCallback") { if (targetMon?.hasItem("terrainextender") == true) 8 else 5 }
                on("SetStatus") {
                    val status = relay as EffectLike
                    val t = pokemon
                    val e = sourceEffect
                    if (status.id == "slp" && Js.truthy(t.isGrounded()) && !t.isSemiInvulnerable()) {
                        if (e?.id == "yawn" || (e?.effectType == "Move" && !hasSecondaries(e))) add("-activate", t, "move: Electric Terrain")
                        return@on false
                    }
                    Unit
                }
                on("TryAddVolatile") {
                    val status = relay as EffectLike
                    val t = pokemon
                    if (!Js.truthy(t.isGrounded()) || t.isSemiInvulnerable()) return@on Unit
                    if (status.id == "yawn") {
                        add("-activate", t, "move: Electric Terrain")
                        return@on null
                    }
                    Unit
                }
                on("BasePower") {
                    val attacker = pokemon
                    if (move.type == "Electric" && Js.truthy(attacker.isGrounded()) && !attacker.isSemiInvulnerable()) {
                        chainModify(intArrayOf(5325, 4096))
                    } else Unit
                }
                on("FieldStart") {
                    val e = sourceEffect
                    if (e?.effectType == "Ability") add("-fieldstart", "move: Electric Terrain", "[from] ability: " + e.name, "[of] $source")
                    else add("-fieldstart", "move: Electric Terrain")
                    Unit
                }
                on("FieldEnd") { add("-fieldend", "move: Electric Terrain"); Unit }
            }
        }
        pledge("grasspledge", listOf("waterpledge", "firepledge"), "Grass Pledge", iterateAll = true) { m ->
            if (m.sourceEffect == "waterpledge") {
                m.type = "Grass"; m.forceSTAB = true; m.sideCondition = "grasspledge"
            }
            if (m.sourceEffect == "firepledge") {
                m.type = "Fire"; m.forceSTAB = true; m.sideCondition = "firepledge"
            }
        }
        move("grasspledge") {
            condition { on("ModifySpe") { chainModify(0.25) } }
        }
        pledge("waterpledge", listOf("firepledge", "grasspledge"), "Water Pledge", iterateAll = false) { m ->
            if (m.sourceEffect == "grasspledge") {
                m.type = "Grass"; m.forceSTAB = true; m.sideCondition = "grasspledge"
            }
            if (m.sourceEffect == "firepledge") {
                m.type = "Water"; m.forceSTAB = true; m.self = SideConditionHit("waterpledge")
            }
        }
        move("waterpledge") {
            condition {
                on("ModifyMove") {
                    val m = relay as ActiveMove
                    val p = pokemon
                    val list = m.secondaries
                    if (list != null && m.id != "secretpower") {
                        for ((i, secondary) in list.withIndex()) {
                            if (p.hasAbility("serenegrace") && secondary.hitVolatileStatus == "flinch") continue
                            val chance = secondary.chance
                            if (chance != null && chance != 0) list[i] = ChanceHit(secondary, chance * 2)
                        }
                        val self = m.self
                        val selfChance = self?.chance
                        if (self != null && selfChance != null && selfChance != 0) m.self = ChanceHit(self, selfChance * 2)
                    }
                    Unit
                }
            }
        }
        move("darkvoid") {
            on("Try") {
                val src = pokemon
                if (src.species.name == "Darkrai" || move.hasBounced) return@on Unit
                add("-fail", src, "move: Dark Void")
                hint("Only a Pokemon whose form is Darkrai can use this move.")
                null
            }
        }
        move("spiritshackle") {
            secondary {
                on("Hit") {
                    val src = sourceMon!!
                    if (src.isActive) pokemon.addVolatile("trapped", src, move, "trapper")
                    Unit
                }
            }
        }
        move("beakblast") {
            callback("priorityChargeCallback") { pokemon.addVolatile("beakblast"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "move: Beak Blast"); Unit }
                on("Hit") {
                    val src = sourceMon!!
                    if (battle.checkMoveMakesContact(move, src, pokemon)) src.trySetStatus("brn", pokemon)
                    Unit
                }
            }
            on("AfterMove") { pokemon.removeVolatile("beakblast"); Unit }
        }
        move("stockpile") {
            on("Try") {
                val v = pokemon.volatiles["stockpile"]
                if (v != null && v.int("layers") >= 3) false else Unit
            }
            condition {
                on("Start") {
                    val t = pokemon
                    state["layers"] = 1
                    state["def"] = 0
                    state["spd"] = 0
                    add("-start", t, "stockpile" + state.int("layers"))
                    stockpileBoost(this, t)
                    Unit
                }
                on("Restart") {
                    val t = pokemon
                    if (state.int("layers") >= 3) return@on false
                    state["layers"] = state.int("layers") + 1
                    add("-start", t, "stockpile" + state.int("layers"))
                    stockpileBoost(this, t)
                    Unit
                }
                on("End") {
                    val t = pokemon
                    if (state.int("def") != 0 || state.int("spd") != 0) {
                        val b = LinkedHashMap<String, Int>()
                        if (state.int("def") != 0) b["def"] = state.int("def")
                        if (state.int("spd") != 0) b["spd"] = state.int("spd")
                        boost(b, t, t)
                    }
                    add("-end", t, "Stockpile")
                    if (state.int("def") != state.int("layers") * -1 || state.int("spd") != state.int("layers") * -1) {
                        hint("In Gen 7, Stockpile keeps track of how many times it successfully altered each stat individually.")
                    }
                    Unit
                }
            }
        }
        move("ragingfury") {
            on("AfterMove") {
                if (pokemon.volatiles["lockedmove"]?.duration == 1) pokemon.removeVolatile("lockedmove")
                Unit
            }
        }
        move("rollout") {
            callback("basePowerCallback") {
                val p = pokemon
                var bp = move.basePower.toDouble()
                val data = p.volatiles["rollout"]
                if (data != null && Js.truthy(data["hitCount"])) bp *= Math.pow(2.0, data.int("contactHitCount").toDouble())
                if (data != null && p.status != "slp") {
                    data["hitCount"] = data.int("hitCount") + 1
                    data["contactHitCount"] = data.int("contactHitCount") + 1
                    if (data.int("hitCount") < 5) data.duration = 2
                }
                if (p.volatiles["defensecurl"] != null) bp *= 2
                Js.number(bp)
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                val p = pokemon
                val t = sourceMon
                if (p.volatiles["rollout"] != null || p.status == "slp" || t == null) return@on Unit
                p.addVolatile("rollout")
                p.volatiles["rollout"]!!["targetSlot"] = if (m.sourceEffect.isNotEmpty()) p.lastMoveTargetLoc else p.getLocOf(t)
                Unit
            }
            on("AfterMove") {
                val src = pokemon
                val data = src.volatiles["rollout"]
                if (data != null && data.int("hitCount") == 5 && data.int("contactHitCount") < 5) {
                    src.addVolatile("rolloutstorage")
                    src.volatiles["rolloutstorage"]!!["contactHitCount"] = data["contactHitCount"]
                }
                Unit
            }
            condition {
                on("Start") {
                    state["hitCount"] = 0
                    state["contactHitCount"] = 0
                    Unit
                }
                on("Residual") {
                    val t = pokemon
                    if (t.lastMove != null && t.lastMove!!.id == "struggle") t.volatiles.remove("rollout")
                    Unit
                }
            }
        }
        move("lashout") {
            on("BasePower") { if (pokemon.statsLoweredThisTurn) chainModify(2) else Unit }
        }
        move("axekick") {
            on("MoveFail") {
                val src = sourceMon!!
                damage(src.baseMaxhp / 2.0, src, src, dex.condition("High Jump Kick"))
                Unit
            }
        }
        move("steelroller") {
            on("Try") { !field.isTerrain("") }
            on("Hit") { field.clearTerrain(); Unit }
            on("AfterSubDamage") { field.clearTerrain(); Unit }
        }
        move("ingrain") {
            condition {
                on("Start") { add("-start", target, "move: Ingrain"); Unit }
                on("Residual") { heal(pokemon.baseMaxhp / 16.0); Unit }
                on("TrapPokemon") { pokemon.tryTrap(); Unit }
                on("DragOut") { add("-activate", target, "move: Ingrain"); null }
            }
        }
        move("jawlock") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                src.addVolatile("trapped", t, move, "trapper")
                t.addVolatile("trapped", src, move, "trapper")
                Unit
            }
        }
        move("simplebeam") {
            on("TryHit") {
                val t = pokemon
                if (t.getAbility().flag("cantsuppress") || t.ability == "simple" || t.ability == "truant") false else Unit
            }
            on("Hit") {
                val p = pokemon
                val oldAbility = p.setAbility("simple")
                if (Js.truthy(oldAbility)) {
                    add("-ability", p, "Simple", "[from] move: Simple Beam")
                    return@on Unit
                }
                oldAbility
            }
        }
        move("venoshock") {
            on("BasePower") {
                val t = sourceMon!!
                if (t.status == "psn" || t.status == "tox") chainModify(2) else Unit
            }
        }
        move("assurance") {
            callback("basePowerCallback") { if (Js.truthy(sourceMon!!.hurtThisTurn)) move.basePower * 2 else move.basePower }
        }
        move("payback") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                if (t.newlySwitched || battle.queue.willMove(t) != null) move.basePower else move.basePower * 2
            }
        }
        move("topsyturvy") {
            on("Hit") {
                val t = pokemon
                var success = false
                for (key in t.boosts.keys.toList()) {
                    if (t.boosts[key] == 0) continue
                    t.boosts[key] = -(t.boosts[key] ?: 0)
                    success = true
                }
                if (!success) return@on false
                add("-invertboost", t, "[from] move: Topsy-Turvy")
                Unit
            }
        }
        move("fellstinger") {
            on("AfterMoveSecondarySelf") {
                val p = pokemon
                val t = sourceMon
                if (t == null || t.fainted || t.hp <= 0) boost(linkedMapOf("atk" to 3), p, p, move)
                Unit
            }
        }
        move("smackdown") {
            condition {
                on("Start") {
                    val p = pokemon
                    var applies = false
                    if (p.hasType("Flying") || p.hasAbility("levitate")) applies = true
                    if (p.hasItem("ironball") || p.volatiles["ingrain"] != null || field.getPseudoWeather("gravity") != null) applies = false
                    if (p.removeVolatile("fly") || p.removeVolatile("bounce")) {
                        applies = true
                        battle.queue.cancelMove(p)
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
                    if (!applies) return@on false
                    add("-start", p, "Smack Down")
                    Unit
                }
                on("Restart") {
                    val p = pokemon
                    if (p.removeVolatile("fly") || p.removeVolatile("bounce")) {
                        battle.queue.cancelMove(p)
                        p.removeVolatile("twoturnmove")
                        add("-start", p, "Smack Down")
                    }
                    Unit
                }
            }
        }
    }

    private fun hasSecondaries(e: EffectLike): Boolean =
        if (e is ActiveMove) e.secondaries != null else e.data("secondaries") != null

    private fun stockpileBoost(call: HookCall, t: Pokemon) = with(call) {
        val curDef = t.boosts["def"]
        val curSpD = t.boosts["spd"]
        boost(linkedMapOf("def" to 1, "spd" to 1), t, t)
        if (curDef != t.boosts["def"]) state["def"] = state.int("def") - 1
        if (curSpD != t.boosts["spd"]) state["spd"] = state.int("spd") - 1
    }

    private fun mortalSpin(call: HookCall, p: Pokemon) = with(call) {
        if (move.hasSheerForce) return@with
        if (p.hp != 0 && p.removeVolatile("leechseed")) add("-end", p, "Leech Seed", "[from] move: Mortal Spin", "[of] $p")
        for (condition in listOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")) {
            if (p.hp != 0 && p.side.removeSideCondition(condition)) {
                add("-sideend", p.side, dex.condition(condition).name, "[from] move: Mortal Spin", "[of] $p")
            }
        }
        if (p.hp != 0 && p.volatiles["partiallytrapped"] != null) p.removeVolatile("partiallytrapped")
    }

    /** Two-turn moves whose first turn is a plain charge (Dig, Fly, Bounce ...). */
    private fun HookRegistrar.chargeMove(id: String, conditionBody: jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks.() -> Unit) = move(id) {
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
        condition(conditionBody)
    }

    /** The shared parts of the pledge moves: the combo base power, the wait for the partner, and the side condition. */
    private fun HookRegistrar.pledge(id: String, partners: List<String>, label: String, iterateAll: Boolean, modify: (ActiveMove) -> Unit) = move(id) {
        callback("basePowerCallback") {
            if (move.sourceEffect in partners) {
                add("-combine")
                150
            } else move.basePower
        }
        on("PrepareHit") {
            val src = sourceMon!!
            for (action in battle.queue.list.toList()) {
                if (!iterateAll && action.choice != "move") continue
                val other = action.move
                val user = action.pokemon
                if (other == null || user == null || !user.isActive || user.fainted || action.maxMove != null) continue
                if (user.isAlly(src) && other.id in partners) {
                    battle.queue.prioritizeAction(action, move)
                    add("-waiting", src, user)
                    return@on null
                }
            }
            Unit
        }
        on("ModifyMove") { modify(relay as ActiveMove); Unit }
        condition {
            on("SideStart") { add("-sidestart", target, label); Unit }
            on("SideEnd") { add("-sideend", target, label); Unit }
        }
    }
    private fun HookRegistrar.batch4() {
        move("grassyterrain") {
            condition {
                callback("durationCallback") { if (targetMon?.hasItem("terrainextender") == true) 8 else 5 }
                on("BasePower") {
                    val attacker = pokemon
                    val defender = sourceMon!!
                    val m = move
                    if (m.id in listOf("earthquake", "bulldoze", "magnitude") && Js.truthy(defender.isGrounded()) && !defender.isSemiInvulnerable()) {
                        return@on chainModify(0.5)
                    }
                    if (m.type == "Grass" && Js.truthy(attacker.isGrounded())) return@on chainModify(intArrayOf(5325, 4096))
                    Unit
                }
                on("FieldStart") {
                    val e = sourceEffect
                    if (e?.effectType == "Ability") add("-fieldstart", "move: Grassy Terrain", "[from] ability: " + e.name, "[of] $source")
                    else add("-fieldstart", "move: Grassy Terrain")
                    Unit
                }
                on("Residual") {
                    val p = pokemon
                    if (Js.truthy(p.isGrounded()) && !p.isSemiInvulnerable()) heal(p.baseMaxhp / 16.0, p, p)
                    Unit
                }
                on("FieldEnd") { add("-fieldend", "move: Grassy Terrain"); Unit }
            }
        }
        move("recycle") {
            on("Hit") {
                val p = pokemon
                if (p.item.isNotEmpty() || p.lastItem.isEmpty()) return@on false
                val item = p.lastItem
                p.lastItem = ""
                add("-item", p, dex.item(item), "[from] move: Recycle")
                p.setItem(item)
                Unit
            }
        }
        move("lastresort") {
            on("Try") {
                val src = pokemon
                if (src.moveSlots.size < 2) return@on false
                var hasLastResort = false
                for (slot in src.moveSlots) {
                    if (slot.id == "lastresort") {
                        hasLastResort = true
                        continue
                    }
                    if (!slot.used) return@on false
                }
                hasLastResort
            }
        }
        move("hardpress") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                val inner = Math.floor(t.hp * 4096.0 / t.maxhp)
                val bp = Math.floor(Math.floor((100 * (100 * inner) + 2048 - 1) / 4096) / 100).toInt()
                if (bp != 0) bp else 1
            }
        }
        move("metronome") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val moves = dex.allMoves.filter { m ->
                    val ns = m.data("isNonstandard")
                    (!Js.truthy(ns) || ns == "Unobtainable") && m.flag("metronome")
                }.sortedBy { it.num }
                var randomMove = ""
                if (moves.isNotEmpty()) randomMove = battle.sample(moves).id
                if (randomMove.isEmpty()) return@on false
                src.side.lastSelectedMove = Js.toID(randomMove)
                useMoveUndefinedTarget(battle, randomMove, t)
                Unit
            }
        }
        move("gastroacid") {
            on("TryHit") {
                val t = pokemon
                if (t.getAbility().flag("cantsuppress")) return@on false
                if (t.hasItem("abilityshield")) {
                    add("-block", t, "item: Ability Shield")
                    return@on null
                }
                Unit
            }
            condition {
                on("Start") {
                    val p = pokemon
                    if (p.hasItem("abilityshield")) return@on false
                    add("-endability", p)
                    battle.singleEvent("End", p.getAbility(), p.abilityState, p, p, "gastroacid")
                    Unit
                }
                on("Copy") {
                    val p = pokemon
                    if (p.getAbility().flag("cantsuppress")) p.removeVolatile("gastroacid")
                    Unit
                }
            }
        }
        move("doomdesire") {
            on("Try") {
                if (move.extra[FUTURE_HIT] == true) return@on Unit
                val src = pokemon
                val t = sourceMon!!
                if (!Js.truthy(t.side.addSlotCondition(t, "futuremove"))) return@on false
                val data = t.side.slotConditions[t.position].getValue("futuremove")
                data["move"] = "doomdesire"
                data.source = src
                data["moveData"] = dex.activeMove("doomdesire").also { it.extra[FUTURE_HIT] = true }
                add("-start", src, "Doom Desire")
                BattleActions.NOT_FAIL
            }
        }
        move("echoedvoice") {
            callback("basePowerCallback") {
                var bp: Any = move.basePower
                val ev = field.pseudoWeather["echoedvoice"]
                if (ev != null) bp = Js.number(move.basePower * Js.num(ev["multiplier"]))
                bp
            }
            on("Try") { field.addPseudoWeather("echoedvoice"); Unit }
            condition {
                on("FieldStart") { state["multiplier"] = 1; Unit }
                on("FieldRestart") {
                    if (state.duration != 2) {
                        state.duration = 2
                        if (state.int("multiplier") < 5) state["multiplier"] = state.int("multiplier") + 1
                    }
                    Unit
                }
            }
        }
        move("charge") {
            condition {
                on("Start") { chargeStart(this); Unit }
                on("Restart") { chargeStart(this); Unit }
                on("BasePower") { if (move.type == "Electric") chainModify(2) else Unit }
                on("MoveAborted") {
                    if (move.type == "Electric" && move.id != "charge") pokemon.removeVolatile("charge")
                    Unit
                }
                on("AfterMove") {
                    if (move.type == "Electric" && move.id != "charge") pokemon.removeVolatile("charge")
                    Unit
                }
                on("End") { add("-end", target, "Charge", "[silent]"); Unit }
            }
        }
        move("block") {
            on("Hit") { pokemon.addVolatile("trapped", sourceMon, move, "trapper") }
        }
        chargeMove("bounce") {
            on("Invulnerability") {
                if (move.id in listOf("gust", "twister", "skyuppercut", "thunder", "hurricane", "smackdown", "thousandarrows")) Unit else false
            }
            on("SourceBasePower") { if (move.id == "gust" || move.id == "twister") chainModify(2) else Unit }
        }
        move("spite") {
            on("Hit") {
                val t = pokemon
                var m: EffectLike = t.lastMove ?: return@on false
                val last = m as ActiveMove
                if (Js.truthy(last.isZ)) return@on false
                if (Js.truthy(last.isMax) && last.baseMove != null) m = dex.move(last.baseMove!!)!!
                val ppDeducted = t.deductPP(m.id, 4)
                if (ppDeducted == 0) return@on false
                add("-activate", t, "move: Spite", m.name, ppDeducted)
                Unit
            }
        }
        move("guardsplit") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val newdef = Math.floor((t.storedStats.getValue("def") + src.storedStats.getValue("def")) / 2.0).toInt()
                t.storedStats["def"] = newdef
                src.storedStats["def"] = newdef
                val newspd = Math.floor((t.storedStats.getValue("spd") + src.storedStats.getValue("spd")) / 2.0).toInt()
                t.storedStats["spd"] = newspd
                src.storedStats["spd"] = newspd
                add("-activate", src, "move: Guard Split", "[of] $t")
                Unit
            }
        }
        move("stuffcheeks") {
            on("DisableMove") {
                if (!pokemon.getItem().bool("isBerry")) pokemon.disableMove("stuffcheeks")
                Unit
            }
            on("Try") { pokemon.getItem().bool("isBerry") }
            on("Hit") {
                if (!Js.truthy(boost(linkedMapOf("def" to 2)))) return@on null
                pokemon.eatItem(true)
                Unit
            }
        }
        move("lockon") {
            on("TryHit") { if (sourceMon!!.volatiles["lockon"] != null) false else Unit }
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                src.addVolatile("lockon", t)
                add("-activate", src, "move: Lock-On", "[of] $t")
                Unit
            }
            condition {
                on("SourceInvulnerability") {
                    if (activeMove != null && source === state.target && target === state.source) 0 else Unit
                }
                on("SourceAccuracy") {
                    if (activeMove != null && source === state.target && target === state.source) true else Unit
                }
            }
        }
        move("falseswipe") {
            on("Damage") { if (relayNum >= pokemon.hp) pokemon.hp - 1 else Unit }
        }
        move("powerswap") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val targetBoosts = LinkedHashMap<String, Int>()
                val sourceBoosts = LinkedHashMap<String, Int>()
                for (stat in listOf("atk", "spa")) {
                    targetBoosts[stat] = t.boosts[stat] ?: 0
                    sourceBoosts[stat] = src.boosts[stat] ?: 0
                }
                src.setBoost(targetBoosts)
                t.setBoost(sourceBoosts)
                add("-swapboost", src, t, "atk, spa", "[from] move: Power Swap")
                Unit
            }
        }
        move("belch") {
            on("DisableMove") { if (!pokemon.ateBerry) pokemon.disableMove("belch"); Unit }
        }
        move("mimic") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val m = t.lastMove
                if (src.transformed || m == null || m.flag("failmimic") || m.id in src.moves) return@on false
                if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) return@on false
                val mimicIndex = src.moves.indexOf("mimic")
                if (mimicIndex < 0) return@on false
                src.moveSlots[mimicIndex] = MoveSlot(m.name, m.id, m.pp, m.pp, m.target, false, "", false, true)
                add("-start", src, "Mimic", m.name)
                Unit
            }
        }
        move("conversion2") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val last = t.lastMoveUsed ?: return@on false
                val attackType = last.type
                val possibleTypes = ArrayList<String>()
                for (type in dex.typeNames) {
                    if (src.hasType(type)) continue
                    if (dex.effectiveness(attackType, type) == -1 || dex.immune(attackType, type)) possibleTypes.add(type)
                }
                if (possibleTypes.isEmpty()) return@on false
                val randomType = battle.sample(possibleTypes)
                if (!src.setType(randomType)) return@on false
                add("-start", src, "typechange", randomType)
                Unit
            }
        }
        move("filletaway") {
            on("Try") {
                val src = pokemon
                if (src.hp <= src.maxhp / 2.0 || src.maxhp == 1) false else Unit
            }
            on("TryHit") {
                val m = move
                if (!Js.truthy(boost(m.boosts ?: emptyMap()))) return@on null
                m.boosts = null
                Unit
            }
            on("Hit") { battle.directDamage(pokemon.maxhp / 2.0); Unit }
        }
        move("corrosivegas") {
            on("Hit") {
                val t = pokemon
                val src = sourceMon!!
                val item = t.takeItem(src)
                if (item is EffectLike) add("-enditem", t, item.name, "[from] move: Corrosive Gas", "[of] $src")
                else add("-fail", t, "move: Corrosive Gas")
                Unit
            }
        }
        move("happyhour") {
            on("TryHit") { add("-activate", target, "move: Happy Hour"); Unit }
        }
        move("retaliate") {
            on("BasePower") { if (pokemon.side.faintedLastTurn != null) chainModify(2) else Unit }
        }
        gmaxSelf("gmaxcentiferno") { src -> for (p in src.foes()) p.addVolatile("partiallytrapped", src, src.battle.dex.activeMove("G-Max Centiferno")) }
        gmaxSelf("gmaxsandblast") { src -> for (p in src.foes()) p.addVolatile("partiallytrapped", src, src.battle.dex.activeMove("G-Max Sandblast")) }
        gmaxSelf("gmaxgoldrush") { src -> for (p in src.foes()) p.addVolatile("confusion") }
        gmaxSelf("gmaxsweetness") { src -> for (ally in src.side.pokemon) ally.cureStatus() }
        gmaxSelf("gmaxdepletion") { src ->
            for (p in src.foes()) {
                var m: EffectLike = p.lastMove ?: continue
                val last = m as ActiveMove
                if (Js.truthy(last.isZ)) continue
                if (Js.truthy(last.isMax) && last.baseMove != null) m = src.battle.dex.move(last.baseMove!!)!!
                val ppDeducted = p.deductPP(m.id, 2)
                if (ppDeducted != 0) src.battle.add("-activate", p, "move: G-Max Depletion", m.name, ppDeducted)
            }
        }
        move("gmaxmeltdown") {
            self {
                on("Hit") {
                    val src = pokemon
                    for (p in src.foes()) if (p.volatiles["dynamax"] == null) p.addVolatile("torment", src, sourceEffect)
                    Unit
                }
            }
        }
        for (id in listOf("gmaxsteelsurge", "gmaxvinelash", "gmaxwildfire")) gmaxSelf(id) { src ->
            for (side in src.side.foeSidesWithConditions()) side.addSideCondition(id)
        }
        move("gmaxsteelsurge") {
            condition {
                on("SideStart") { add("-sidestart", target, "move: G-Max Steelsurge"); Unit }
                on("EntryHazard") {
                    val p = pokemon
                    if (p.hasItem("heavydutyboots")) return@on Unit
                    val steelHazard = dex.activeMove("Stealth Rock")
                    steelHazard.type = "Steel"
                    val typeMod = Js.clampIntRange(p.runEffectiveness(steelHazard), -6, 6)
                    damage(p.maxhp * Math.pow(2.0, typeMod.toDouble()) / 8)
                    Unit
                }
            }
        }
        gmaxField("gmaxvinelash", "G-Max Vine Lash", "Grass")
        gmaxField("gmaxwildfire", "G-Max Wildfire", "Fire")
    }

    const val FUTURE_HIT = "futureHit"

    private fun chargeStart(call: HookCall) = with(call) {
        val e = sourceEffect
        if (e != null && e.name in listOf("Electromorphosis", "Wind Power")) {
            add("-start", target, "Charge", battle.activeMove!!.name, "[from] ability: " + e.name)
        } else add("-start", target, "Charge")
    }

    private fun HookRegistrar.gmaxSelf(id: String, body: (Pokemon) -> Unit) = move(id) {
        self { on("Hit") { body(pokemon); Unit } }
    }

    private fun HookRegistrar.gmaxField(id: String, label: String, immuneType: String) = move(id) {
        condition {
            on("SideStart") { add("-sidestart", target, label); Unit }
            on("Residual") {
                val t = pokemon
                if (!t.hasType(immuneType)) damage(t.baseMaxhp / 6.0, t)
                Unit
            }
            on("SideEnd") { add("-sideend", target, label); Unit }
        }
    }
}

/** A secondary (or self) block whose chance a handler changed, as `secondary.chance *= 2` does on the copied move. */
private class ChanceHit(val base: HitData, override val chance: Int?) : HitData by base

/** A literal `{ sideCondition: id }` hit block, as Water Pledge sets for `move.self`. */
private class SideConditionHit(override val hitSideCondition: String) : HitData {
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
    override val hitBoosts: Map<String, Int>? get() = null
    override val hitHeal: IntArray? get() = null
    override val hitStatus: String? get() = null
    override val hitForceStatus: String? get() = null
    override val hitVolatileStatus: String? get() = null
    override val hitSlotCondition: String? get() = null
    override val hitWeather: String? get() = null
    override val hitTerrain: String? get() = null
    override val hitPseudoWeather: String? get() = null
    override val hitForceSwitch: Boolean get() = false
    override val hitSelfdestruct: String? get() = null
    override val hitSelfSwitch: Any? get() = null
    override val hitSelf: HitData? get() = null
    override val hitSecondaries: List<HitData>? get() = null
    override val chance: Int? get() = null
    override val hitAbility: EffectLike? get() = null
}
