package jbro.cobblemon.mcc.betterai.engine.effects

import com.google.gson.JsonObject
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.MoveData
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookFn
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.MoveSlot
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove
import kotlin.math.ceil
import kotlin.math.floor

/** Moves with code in `data/moves.js`, second batch (port list A). */
object PortMovesA : HookSet() {
    private val NOT_FAIL = BattleActions.NOT_FAIL

    @Suppress("UNCHECKED_CAST")
    private fun boosts(value: Any?): MutableMap<String, Int> = value as MutableMap<String, Int>

    override fun HookRegistrar.define() {
        move("wideguard") {
            on("Try") { battle.queue.willAct() != null }
            on("HitSide") { sourceMon!!.addVolatile("stall"); Unit }
            condition {
                on("SideStart") { add("-singleturn", source, "Wide Guard"); Unit }
                on("TryHit") {
                    val m = move
                    if (m.target != "allAdjacent" && m.target != "allAdjacentFoes") return@on Unit
                    if (Js.truthy(m.isZ) || Js.truthy(m.isMax)) {
                        if (m.id in listOf("gmaxoneblow", "gmaxrapidflow")) return@on Unit
                        pokemon.getMoveHitData(m).zBrokeProtect = true
                        return@on Unit
                    }
                    add("-activate", target, "move: Wide Guard")
                    val src = sourceMon!!
                    if (src.getVolatile("lockedmove") != null && src.volatiles["lockedmove"]?.duration == 2) src.volatiles.remove("lockedmove")
                    NOT_FAIL
                }
            }
        }
        move("batonpass") {
            on("Hit") {
                val t = pokemon
                if (battle.canSwitch(t.side) == 0 || t.volatiles["commanded"] != null) {
                    battle.attrLastMove("[still]")
                    add("-fail", t)
                    return@on NOT_FAIL
                }
                Unit
            }
            self { on("Hit") { pokemon.skipBeforeSwitchOutEventFlag = true; Unit } }
        }
        move("roost") {
            condition {
                on("Start") {
                    val t = pokemon
                    if (t.terastallized != null) {
                        if (t.hasType("Flying")) add("-hint", "If a Terastallized Pokemon uses Roost, it remains Flying-type.")
                        return@on false
                    }
                    add("-singleturn", t, "move: Roost")
                    Unit
                }
                on("Type") {
                    state["typeWas"] = relay
                    @Suppress("UNCHECKED_CAST")
                    (relay as List<String>).filter { it != "Flying" }
                }
            }
        }
        move("expandingforce") {
            on("BasePower") {
                if (field.isTerrain("psychicterrain") && pokemon.isGrounded() == true) chainModify(1.5) else Unit
            }
            on("ModifyMove") {
                if (field.isTerrain("psychicterrain") && pokemon.isGrounded() == true) (relay as ActiveMove).target = "allAdjacentFoes"
                Unit
            }
        }
        move("weatherball") {
            on("ModifyType") {
                val m = relay as ActiveMove
                when (pokemon.effectiveWeather()) {
                    "sunnyday", "desolateland" -> m.type = "Fire"
                    "raindance", "primordialsea" -> m.type = "Water"
                    "sandstorm" -> m.type = "Rock"
                    "hail", "snow" -> m.type = "Ice"
                }
                Unit
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                when (pokemon.effectiveWeather()) {
                    "sunnyday", "desolateland", "raindance", "primordialsea", "sandstorm", "hail", "snow" -> m.basePower *= 2
                }
                Unit
            }
        }
        move("destinybond") {
            on("PrepareHit") { !pokemon.removeVolatile("destinybond") }
            condition {
                on("Start") { add("-singlemove", pokemon, "Destiny Bond"); Unit }
                on("Faint") {
                    val src = sourceMon
                    val e = sourceEffect
                    if (src == null || e == null || pokemon.isAlly(src)) return@on Unit
                    if (e.effectType == "Move" && !e.flag("futuremove")) {
                        if (src.volatiles["dynamax"] != null) {
                            add("-hint", "Dynamaxed Pokémon are immune to Destiny Bond.")
                            return@on Unit
                        }
                        add("-activate", pokemon, "move: Destiny Bond")
                        src.faint()
                    }
                    Unit
                }
                on("BeforeMove") {
                    if (move.id == "destinybond") return@on Unit
                    pokemon.removeVolatile("destinybond")
                    Unit
                }
                on("MoveAborted") { pokemon.removeVolatile("destinybond"); Unit }
            }
        }
        move("painsplit") {
            on("Hit") {
                val t = pokemon
                val p = sourceMon!!
                val targetHP = t.getUndynamaxedHP()
                val averagehp = floor((targetHP + p.hp) / 2.0).toInt().takeIf { it != 0 } ?: 1
                val targetChange = targetHP - averagehp
                t.sethp((t.hp - targetChange).toDouble())
                add("-sethp", t, t.getHealth, "[from] move: Pain Split", "[silent]")
                p.sethp(averagehp.toDouble())
                add("-sethp", p, p.getHealth, "[from] move: Pain Split")
                Unit
            }
        }
        move("grassknot") {
            callback("basePowerCallback") {
                val w = sourceMon!!.getWeight()
                when {
                    w >= 2000 -> 120
                    w >= 1000 -> 100
                    w >= 500 -> 80
                    w >= 250 -> 60
                    w >= 100 -> 40
                    else -> 20
                }
            }
            on("TryHit") {
                if (pokemon.volatiles["dynamax"] != null) {
                    add("-fail", source, "move: Grass Knot", "[from] Dynamax")
                    battle.attrLastMove("[still]")
                    null
                } else Unit
            }
        }
        move("lowkick") {
            callback("basePowerCallback") {
                val w = sourceMon!!.getWeight()
                when {
                    w >= 2000 -> 120
                    w >= 1000 -> 100
                    w >= 500 -> 80
                    w >= 250 -> 60
                    w >= 100 -> 40
                    else -> 20
                }
            }
            on("TryHit") {
                if (pokemon.volatiles["dynamax"] != null) {
                    add("-fail", source, "Dynamax")
                    battle.attrLastMove("[still]")
                    null
                } else Unit
            }
        }
        move("stickyweb") {
            condition {
                on("SideStart") { add("-sidestart", target, "move: Sticky Web"); Unit }
                on("EntryHazard") {
                    val p = pokemon
                    if (p.isGrounded() != true || p.hasItem("heavydutyboots")) return@on Unit
                    add("-activate", p, "move: Sticky Web")
                    battle.boost(mapOf("spe" to -1), p, p.side.foe.active[0], dex.activeMove("stickyweb"))
                    Unit
                }
            }
        }
        move("storedpower") {
            callback("basePowerCallback") { move.basePower + 20 * pokemon.positiveBoosts() }
        }
        move("powertrip") {
            callback("basePowerCallback") { move.basePower + 20 * pokemon.positiveBoosts() }
        }
        move("endure") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "move: Endure"); Unit }
                on("Damage") {
                    if (sourceEffect?.effectType == "Move" && relayNum >= pokemon.hp) {
                        add("-activate", target, "move: Endure")
                        pokemon.hp - 1
                    } else Unit
                }
            }
        }
        move("detect") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
        }
        move("healingwish") {
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
                    if (!t.fainted && (t.hp < t.maxhp || t.status.isNotEmpty())) {
                        t.heal(t.maxhp.toDouble())
                        t.clearStatus()
                        add("-heal", t, t.getHealth, "[from] move: Healing Wish")
                        t.side.removeSlotCondition(t, "healingwish")
                    }
                    Unit
                }
            }
        }
        move("brickbreak") {
            on("TryHit") {
                val side = pokemon.side
                side.removeSideCondition("reflect")
                side.removeSideCondition("lightscreen")
                side.removeSideCondition("auroraveil")
                Unit
            }
        }
        move("ivycudgel") {
            on("PrepareHit") {
                if (move.type != "Grass") battle.attrLastMove("[anim] Ivy Cudgel " + move.type)
                Unit
            }
            on("ModifyType") {
                val m = relay as ActiveMove
                when (pokemon.species.name) {
                    "Ogerpon-Wellspring", "Ogerpon-Wellspring-Tera" -> m.type = "Water"
                    "Ogerpon-Hearthflame", "Ogerpon-Hearthflame-Tera" -> m.type = "Fire"
                    "Ogerpon-Cornerstone", "Ogerpon-Cornerstone-Tera" -> m.type = "Rock"
                }
                Unit
            }
        }
        move("perishsong") {
            on("HitField") {
                val src = sourceMon!!
                val m = move
                var result = false
                var message = false
                for (p in battle.getAllActive()) {
                    if (battle.runEvent("Invulnerability", p, src, m) == false) {
                        add("-miss", src, p)
                        result = true
                    } else if (battle.runEvent("TryHit", p, src, m) == null) {
                        result = true
                    } else if (p.volatiles["perishsong"] == null) {
                        p.addVolatile("perishsong")
                        add("-start", p, "perish3", "[silent]")
                        result = true
                        message = true
                    }
                }
                if (!result) return@on false
                if (message) add("-fieldactivate", "move: Perish Song")
                Unit
            }
        }
        move("hex") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                if (t.status.isNotEmpty() || t.hasAbility("comatose")) move.basePower * 2 else move.basePower
            }
        }
        move("infernalparade") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                if (t.status.isNotEmpty() || t.hasAbility("comatose")) move.basePower * 2 else move.basePower
            }
        }
        move("tripleaxel") {
            callback("basePowerCallback") { 20 * move.hit }
        }
        move("triplekick") {
            callback("basePowerCallback") { 10 * move.hit }
        }
        crashMove("supercellslam", "Supercell Slam")
        crashMove("highjumpkick", "High Jump Kick")
        move("upperhand") {
            on("TryHit") {
                val action = battle.queue.willMove(pokemon)
                val m = if (action?.choice == "move") action.move else null
                if (m == null || m.priority <= 0.1 || m.category == "Status") false else Unit
            }
        }
        move("spikes") {
            condition {
                on("SideStart") {
                    add("-sidestart", target, "Spikes")
                    state["layers"] = 1
                    Unit
                }
                on("SideRestart") {
                    if (state.int("layers") >= 3) return@on false
                    add("-sidestart", target, "Spikes")
                    state["layers"] = state.int("layers") + 1
                    Unit
                }
                on("EntryHazard") {
                    val p = pokemon
                    if (p.isGrounded() != true || p.hasItem("heavydutyboots")) return@on Unit
                    val damageAmounts = intArrayOf(0, 3, 4, 6)
                    damage(damageAmounts[state.int("layers")] * p.maxhp / 24.0)
                    Unit
                }
            }
        }
        hpScaled("eruption")
        hpScaled("waterspout")
        move("moonlight") { on("Hit") { weatherHeal(this) } }
        move("synthesis") { on("Hit") { weatherHeal(this) } }
        move("gyroball") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                var power = floor(25.0 * t.getStat("spe") / pokemon.getStat("spe")) + 1
                if (power.isNaN() || power.isInfinite()) power = 1.0
                if (power > 150) power = 150.0
                Js.number(power)
            }
        }
        move("afteryou") {
            on("Hit") {
                if (battle.activePerHalf == 1) return@on false
                val action = battle.queue.willMove(pokemon)
                if (action != null) {
                    battle.queue.prioritizeAction(action)
                    add("-activate", target, "move: After You")
                    Unit
                } else false
            }
        }
        move("disable") {
            on("TryHit") {
                val lm = pokemon.lastMove
                if (lm == null || Js.truthy(lm.isZ) || Js.truthy(lm.isMax) || lm.id == "struggle") false else Unit
            }
        }
        move("shedtail") {
            on("TryHit") {
                val s = pokemon
                if (battle.canSwitch(s.side) == 0 || s.volatiles["commanded"] != null) {
                    add("-fail", s)
                    return@on NOT_FAIL
                }
                if (s.volatiles["substitute"] != null) {
                    add("-fail", s, "move: Shed Tail")
                    return@on NOT_FAIL
                }
                if (s.hp <= ceil(s.maxhp / 2.0)) {
                    add("-fail", s, "move: Shed Tail", "[weak]")
                    return@on NOT_FAIL
                }
                Unit
            }
            on("Hit") { battle.directDamage(ceil(pokemon.maxhp / 2.0)); Unit }
            self { on("Hit") { pokemon.skipBeforeSwitchOutEventFlag = true; Unit } }
        }
        move("revivalblessing") {
            on("TryHit") { if (pokemon.side.pokemon.none { it.fainted }) false else Unit }
        }
        move("heatcrash") {
            callback("basePowerCallback") {
                val targetWeight = sourceMon!!.getWeight()
                val pokemonWeight = pokemon.getWeight()
                when {
                    pokemonWeight >= targetWeight * 5 -> 120
                    pokemonWeight >= targetWeight * 4 -> 100
                    pokemonWeight >= targetWeight * 3 -> 80
                    pokemonWeight >= targetWeight * 2 -> 60
                    else -> 40
                }
            }
            on("TryHit") {
                if (pokemon.volatiles["dynamax"] != null) {
                    add("-fail", source, "Dynamax")
                    battle.attrLastMove("[still]")
                    null
                } else Unit
            }
        }
        move("imprison") {
            condition {
                on("Start") { add("-start", target, "move: Imprison"); Unit }
                on("FoeDisableMove") {
                    for (slot in state.source!!.moveSlots) {
                        if (slot.id == "struggle") continue
                        pokemon.disableMove(slot.id, true)
                    }
                    pokemon.maybeDisabled = true
                    Unit
                }
                on("FoeBeforeMove") {
                    val m = move
                    if (m.id != "struggle" && state.source!!.hasMove(m.id) != null && !Js.truthy(m.isZ) && !Js.truthy(m.isMax)) {
                        add("cant", pokemon, "move: Imprison", m)
                        false
                    } else Unit
                }
            }
        }
        move("psychup") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                for (i in t.boosts.keys) s.boosts[i] = t.boosts.getValue(i)
                for (volatile in listOf("focusenergy", "gmaxchistrike", "laserfocus")) {
                    if (t.volatiles[volatile] != null) {
                        s.addVolatile(volatile)
                        if (volatile == "gmaxchistrike") s.volatiles[volatile]?.set("layers", t.volatiles[volatile]!!["layers"])
                    } else {
                        s.removeVolatile(volatile)
                    }
                }
                add("-copyboost", s, t, "[from] move: Psych Up")
                Unit
            }
        }
        move("fusionbolt") {
            on("BasePower") { if (battle.lastSuccessfulMoveThisTurn == "fusionflare") chainModify(2) else Unit }
        }
        move("fling") {
            on("PrepareHit") {
                val src = sourceMon!!
                val m = move
                if (src.ignoringItem()) return@on false
                val item = src.getItem()
                if (!Js.truthy(battle.singleEvent("TakeItem", item, src.itemState, src, src, m, item))) return@on false
                val fling = item.map("fling") ?: return@on false
                m.basePower = Js.int(fling["basePower"])
                if (item.bool("isBerry")) {
                    m.dynamicHandlers["onHit"] = hook {
                        val foe = pokemon
                        if (Js.truthy(battle.singleEvent("Eat", item, null, foe, null, null))) {
                            battle.runEvent("EatItem", foe, null, null, item)
                            if (item.id == "leppaberry") foe.staleness = "external"
                        }
                        if (Js.truthy(item.handler("onEat"))) foe.ateBerry = true
                        Unit
                    }
                } else if ("fling.effect" in item.declaredHooks) {
                    // Prefer the item's own ported `fling.effect` (registered as `item:<id>/fling` callback `effect`).
                    val ported = jbro.cobblemon.mcc.betterai.engine.hooks.EngineHooks.get("${item.hookKey}/fling", "effect")
                    m.dynamicHandlers["onHit"] = if (ported != null) hook {
                        ported.invoke(HookCall(battle, Unit, target, source, effect, state, self))
                    } else flingEffect(item.id)
                } else {
                    if (m.secondaries == null) m.secondaries = ArrayList()
                    val status = fling["status"] as? String
                    val volatileStatus = fling["volatileStatus"] as? String
                    if (status != null) {
                        m.secondaries!!.add(LiteralHit(hitStatus = status))
                    } else if (volatileStatus != null) {
                        m.secondaries!!.add(LiteralHit(hitVolatileStatus = volatileStatus))
                    }
                }
                src.addVolatile("fling")
                Unit
            }
            condition {
                on("Update") {
                    val p = pokemon
                    val item = p.getItem()
                    p.setItem("")
                    p.lastItem = item.id
                    p.usedItemThisTurn = true
                    add("-enditem", p, item.name, "[from] move: Fling")
                    battle.runEvent("AfterUseItem", p, null, null, item)
                    p.removeVolatile("fling")
                    Unit
                }
            }
        }
        move("focuspunch") {
            callback("priorityChargeCallback") { pokemon.addVolatile("focuspunch"); Unit }
            callback("beforeMoveCallback") {
                if (Js.truthy(pokemon.volatiles["focuspunch"]?.get("lostFocus"))) {
                    add("cant", pokemon, "Focus Punch", "Focus Punch")
                    true
                } else Unit
            }
            condition {
                on("Start") { add("-singleturn", pokemon, "move: Focus Punch"); Unit }
                on("Hit") {
                    if (move.category != "Status") state["lostFocus"] = true
                    Unit
                }
                on("TryAddVolatile") { if ((relay as EffectLike).id == "flinch") null else Unit }
            }
        }
        itemSwap("switcheroo", "move: Switcheroo")
        itemSwap("trick", "move: Trick")
        move("saltcure") {
            condition {
                on("Start") { add("-start", pokemon, "Salt Cure"); Unit }
                on("Residual") {
                    damage(pokemon.baseMaxhp / (if (pokemon.hasType(listOf("Water", "Steel"))) 4.0 else 8.0))
                    Unit
                }
                on("End") { add("-end", pokemon, "Salt Cure"); Unit }
            }
        }
        move("ceaselessedge") {
            on("AfterHit") { ceaselessEdge(this, "spikes"); Unit }
            on("AfterSubDamage") { ceaselessEdge(this, "spikes"); Unit }
        }
        move("soak") {
            on("Hit") {
                val t = pokemon
                if (t.getTypes().joinToString(",") == "Water" || !t.setType("Water")) {
                    add("-fail", t)
                    return@on null
                }
                add("-start", t, "typechange", "Water")
                Unit
            }
        }
        move("ragefist") {
            callback("basePowerCallback") { minOf(350, 50 + 50 * pokemon.timesAttacked) }
        }
        move("futuresight") {
            on("Try") {
                val src = pokemon
                val t = sourceMon!!
                if (!Js.truthy(t.side.addSlotCondition(t, "futuremove"))) return@on false
                val data = t.side.slotConditions[t.position].getValue("futuremove")
                data.duration = 3
                data["move"] = "futuresight"
                data.source = src
                data["moveData"] = futureMoveData(battle)
                add("-start", src, "move: Future Sight")
                NOT_FAIL
            }
        }
        move("entrainment") {
            on("TryHit") {
                val t = pokemon
                val s = sourceMon!!
                if (t === s || t.volatiles["dynamax"] != null) return@on false
                if (t.ability == s.ability || t.getAbility().flag("cantsuppress") || t.ability == "truant" ||
                    s.getAbility().flag("noentrain")) return@on false
                Unit
            }
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val oldAbility = t.setAbility(s.ability)
                if (Js.truthy(oldAbility)) {
                    add("-ability", t, t.getAbility().name, "[from] move: Entrainment")
                    if (!t.isAlly(s)) t.volatileStaleness = "external"
                    return@on Unit
                }
                oldAbility
            }
        }
        move("shellsidearm") {
            on("PrepareHit") {
                if (!sourceMon!!.isAlly(pokemon)) battle.attrLastMove("[anim] Shell Side Arm " + move.category)
                Unit
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                val p = pokemon
                val t = sourceMon ?: return@on Unit
                val atk = p.getStat("atk", false, true)
                val spa = p.getStat("spa", false, true)
                val def = t.getStat("def", false, true)
                val spd = t.getStat("spd", false, true)
                val physical = floor(floor(floor(floor(2.0 * p.level / 5 + 2) * 90 * atk) / def) / 50)
                val special = floor(floor(floor(floor(2.0 * p.level / 5 + 2) * 90 * spa) / spd) / 50)
                if (physical > special || (physical == special && random(2) == 0)) {
                    m.category = "Physical"
                    m.flags["contact"] = 1
                }
                Unit
            }
            on("Hit") {
                if (!sourceMon!!.isAlly(pokemon)) hint(move.category + " Shell Side Arm")
                Unit
            }
            on("AfterSubDamage") {
                if (!sourceMon!!.isAlly(pokemon)) hint(move.category + " Shell Side Arm")
                Unit
            }
        }
        move("allyswitch") {
            on("PrepareHit") { pokemon.addVolatile("allyswitch") }
            on("Hit") {
                val p = pokemon
                var success = true
                if (battle.gameType != "doubles" && battle.gameType != "triples") success = false
                if (p.side.active.size == 3 && p.position == 1) success = false
                val newPosition = if (p.position == 0) p.side.active.size - 1 else 0
                val other = p.side.active[newPosition]
                if (other == null) success = false
                if (other?.fainted == true) success = false
                if (!success) {
                    add("-fail", p, "move: Ally Switch")
                    battle.attrLastMove("[still]")
                    return@on NOT_FAIL
                }
                battle.swapPosition(p, newPosition, "[from] move: Ally Switch")
                Unit
            }
            condition {
                on("Start") { state["counter"] = 3; Unit }
                on("Restart") {
                    val counter = state.int("counter").takeIf { it != 0 } ?: 1
                    val success = randomChance(1, counter)
                    if (!success) {
                        pokemon.volatiles.remove("allyswitch")
                        return@on false
                    }
                    if (state.int("counter") < Js.int(self.data("counterMax"))) state["counter"] = state.int("counter") * 3
                    state.duration = 2
                    Unit
                }
            }
        }
        move("temperflare") {
            callback("basePowerCallback") { if (pokemon.moveLastTurnResult == false) move.basePower * 2 else move.basePower }
        }
        move("mistyexplosion") {
            on("BasePower") {
                if (field.isTerrain("mistyterrain") && pokemon.isGrounded() == true) chainModify(1.5) else Unit
            }
        }
        move("meanlook") {
            on("Hit") { pokemon.addVolatile("trapped", sourceMon, move, "trapper") }
        }
        move("clangoroussoul") {
            on("Try") {
                val s = pokemon
                if (s.hp <= s.maxhp * 33 / 100.0 || s.maxhp == 1) false else Unit
            }
            on("TryHit") {
                if (!Js.truthy(battle.boost(move.boosts ?: emptyMap()))) return@on null
                move.boosts = null
                Unit
            }
            on("Hit") { battle.directDamage(pokemon.maxhp * 33 / 100.0); Unit }
        }
        move("doubleshock") {
            on("TryMove") {
                if (pokemon.hasType("Electric")) return@on Unit
                add("-fail", pokemon, "move: Double Shock")
                battle.attrLastMove("[still]")
                null
            }
            self {
                on("Hit") {
                    val p = pokemon
                    p.setType(p.getTypes(true).map { if (it == "Electric") "???" else it })
                    add("-start", p, "typechange", p.getTypes().joinToString("/"), "[from] move: Double Shock")
                    Unit
                }
            }
        }
        move("burnup") {
            on("TryMove") {
                if (pokemon.hasType("Fire")) return@on Unit
                add("-fail", pokemon, "move: Burn Up")
                battle.attrLastMove("[still]")
                null
            }
            self {
                on("Hit") {
                    val p = pokemon
                    p.setType(p.getTypes(true).map { if (it == "Fire") "???" else it })
                    add("-start", p, "typechange", p.getTypes().joinToString("/"), "[from] move: Burn Up")
                    Unit
                }
            }
        }
        move("electroball") {
            callback("basePowerCallback") {
                var ratio = floor(pokemon.getStat("spe").toDouble() / sourceMon!!.getStat("spe"))
                if (ratio.isNaN() || ratio.isInfinite()) ratio = 0.0
                intArrayOf(40, 60, 80, 120, 150)[minOf(ratio, 4.0).toInt()]
            }
        }
        move("sandsearstorm") {
            on("ModifyMove") {
                val t = sourceMon
                if (t != null && t.effectiveWeather() in listOf("raindance", "primordialsea")) (relay as ActiveMove).accuracy = true
                Unit
            }
        }
        move("bleakwindstorm") {
            on("ModifyMove") {
                val t = sourceMon
                if (t != null && t.effectiveWeather() in listOf("raindance", "primordialsea")) (relay as ActiveMove).accuracy = true
                Unit
            }
        }
        move("glaiverush") {
            condition {
                on("Start") { add("-singlemove", pokemon, "Glaive Rush", "[silent]"); Unit }
                on("Accuracy") { true }
                on("SourceModifyDamage") { chainModify(2) }
                on("BeforeMove") { pokemon.removeVolatile("glaiverush"); Unit }
            }
        }
        move("lunarblessing") {
            on("Hit") {
                val success = Js.truthy(heal(battle.modify(pokemon.maxhp, 0.25)))
                pokemon.cureStatus() || success
            }
        }
        move("junglehealing") {
            on("Hit") {
                val success = Js.truthy(heal(battle.modify(pokemon.maxhp, 0.25)))
                pokemon.cureStatus() || success
            }
        }
        move("orderup") {
            on("AfterMoveSecondarySelf") {
                val p = pokemon
                val commanded = p.volatiles["commanded"] ?: return@on Unit
                val tatsugiri = commanded.source!!
                if (tatsugiri.baseSpecies.baseSpecies != "Tatsugiri") return@on Unit
                when (tatsugiri.baseSpecies.forme) {
                    "Droopy" -> battle.boost(mapOf("def" to 1), p, p)
                    "Stretchy" -> battle.boost(mapOf("spe" to 1), p, p)
                    else -> battle.boost(mapOf("atk" to 1), p, p)
                }
                Unit
            }
        }
        move("syrupbomb") {
            condition {
                on("Start") { add("-start", pokemon, "Syrup Bomb"); Unit }
                on("Residual") { boost(mapOf("spe" to -1)); Unit }
                on("End") { add("-end", pokemon, "Syrup Bomb", "[silent]"); Unit }
            }
        }
        stealEat("bugbite", "[move] Bug Bite")
        stealEat("pluck", "[move] Pluck")
        move("crushgrip") {
            callback("basePowerCallback") {
                val t = sourceMon!!
                val bp = floor(floor((120.0 * (100 * floor(t.hp * 4096.0 / t.maxhp)) + 2048 - 1) / 4096) / 100)
                if (bp == 0.0 || bp.isNaN()) 1 else Js.number(bp)
            }
        }
        move("firepledge") {
            callback("basePowerCallback") {
                if (move.sourceEffect in listOf("grasspledge", "waterpledge")) {
                    add("-combine")
                    150
                } else move.basePower
            }
            on("PrepareHit") { pledgePrepareHit(this, listOf("grasspledge", "waterpledge")) }
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.sourceEffect == "waterpledge") {
                    m.type = "Water"
                    m.forceSTAB = true
                    m.self = PlainHit(hitSideCondition = "waterpledge")
                }
                if (m.sourceEffect == "grasspledge") {
                    m.type = "Fire"
                    m.forceSTAB = true
                    m.sideCondition = "firepledge"
                }
                Unit
            }
            condition {
                on("SideStart") { add("-sidestart", target, "Fire Pledge"); Unit }
                on("Residual") {
                    if (!pokemon.hasType("Fire")) damage(pokemon.baseMaxhp / 8.0, pokemon)
                    Unit
                }
                on("SideEnd") { add("-sideend", target, "Fire Pledge"); Unit }
            }
        }
        move("beatup") {
            callback("basePowerCallback") {
                @Suppress("UNCHECKED_CAST")
                val allies = move.extra["allies"] as MutableList<Pokemon>
                val currentSpecies = allies.removeAt(0).species
                5 + floor(currentSpecies.baseStats.getValue("atk") / 10.0).toInt()
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                val p = pokemon
                val allies = p.side.pokemon.filter { it === p || (!it.fainted && it.status.isEmpty()) }.toMutableList()
                m.extra["allies"] = allies
                m.multihit = allies.size
                Unit
            }
        }
        move("courtchange") {
            on("HitField") {
                val src = sourceMon!!
                var success = false
                val sourceSideConditions = src.side.sideConditions
                val targetSideConditions = src.side.foe.sideConditions
                val sourceTemp = LinkedHashMap<String, EffectState>()
                val targetTemp = LinkedHashMap<String, EffectState>()
                for (id in sourceSideConditions.keys.toList()) {
                    if (id !in COURT_CHANGE) continue
                    sourceTemp[id] = sourceSideConditions.getValue(id)
                    sourceSideConditions.remove(id)
                    success = true
                }
                for (id in targetSideConditions.keys.toList()) {
                    if (id !in COURT_CHANGE) continue
                    targetTemp[id] = targetSideConditions.getValue(id)
                    targetSideConditions.remove(id)
                    success = true
                }
                for ((id, value) in sourceTemp) targetSideConditions[id] = value
                for ((id, value) in targetTemp) sourceSideConditions[id] = value
                add("-swapsideconditions")
                if (!success) return@on false
                add("-activate", src, "move: Court Change")
                Unit
            }
        }
        move("acupressure") {
            on("Hit") {
                val t = pokemon
                val stats = t.boosts.filter { it.value < 6 }.keys.toList()
                if (stats.isNotEmpty()) {
                    val randomStat = battle.sample(stats)
                    boost(mapOf(randomStat to 2))
                    Unit
                } else false
            }
        }
        chargeMove("skyattack")
        chargeMove("freezeshock")
        chargeMove("iceburn")
        move("takeheart") {
            on("Hit") {
                val success = Js.truthy(boost(linkedMapOf("spa" to 1, "spd" to 1)))
                pokemon.cureStatus() || success
            }
        }
        move("heartswap") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val targetBoosts = LinkedHashMap<String, Int>()
                val sourceBoosts = LinkedHashMap<String, Int>()
                for (i in t.boosts.keys) {
                    targetBoosts[i] = t.boosts.getValue(i)
                    sourceBoosts[i] = s.boosts.getValue(i)
                }
                t.setBoost(sourceBoosts)
                s.setBoost(targetBoosts)
                add("-swapboost", s, t, "[from] move: Heart Swap")
                Unit
            }
        }
        move("speedswap") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val targetSpe = t.storedStats.getValue("spe")
                t.storedStats["spe"] = s.storedStats.getValue("spe")
                s.storedStats["spe"] = targetSpe
                add("-activate", s, "move: Speed Swap", "[of] $t")
                Unit
            }
        }
        move("forestscurse") {
            on("Hit") {
                val t = pokemon
                if (t.hasType("Grass")) return@on false
                if (!t.addType("Grass")) return@on false
                add("-start", t, "typeadd", "Grass", "[from] move: Forest's Curse")
                Unit
            }
        }
        room("magicroom", "Magic Room") {
            on("FieldStart") {
                val s = sourceMon
                if (s?.hasAbility("persistent") == true) add("-fieldstart", "move: Magic Room", "[of] $s", "[persistent]")
                else add("-fieldstart", "move: Magic Room", "[of] $s")
                for (mon in battle.getAllActive()) battle.singleEvent("End", mon.getItem(), mon.itemState, mon)
                Unit
            }
            on("FieldEnd") { add("-fieldend", "move: Magic Room", "[of] ${state.source}"); Unit }
        }
        room("wonderroom", "Wonder Room") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                val statAndBoosts = m.overrideOffensiveStat ?: return@on Unit
                if (statAndBoosts !in listOf("def", "spd")) return@on Unit
                m.overrideOffensiveStat = if (statAndBoosts == "def") "spd" else "def"
                hint("${m.name} uses ${if (statAndBoosts == "def") "" else "Sp. "}Def boosts when Wonder Room is active.")
                Unit
            }
            on("FieldStart") {
                val s = sourceMon
                if (s?.hasAbility("persistent") == true) add("-fieldstart", "move: Wonder Room", "[of] $s", "[persistent]")
                else add("-fieldstart", "move: Wonder Room", "[of] $s")
                Unit
            }
            on("FieldEnd") { add("-fieldend", "move: Wonder Room"); Unit }
        }
        move("comeuppance") {
            callback("damageCallback") {
                val lastDamagedBy = pokemon.getLastDamagedBy(true)
                if (lastDamagedBy != null) {
                    val d = lastDamagedBy.damage * 1.5
                    if (d == 0.0) 1 else Js.number(d)
                } else 0
            }
            on("Try") {
                val lastDamagedBy = pokemon.getLastDamagedBy(true)
                if (lastDamagedBy == null || !lastDamagedBy.thisTurn) false else Unit
            }
            on("ModifyTarget") {
                val lastDamagedBy = pokemon.getLastDamagedBy(true)
                @Suppress("UNCHECKED_CAST")
                if (lastDamagedBy != null) (relay as MutableMap<String, Any?>)["target"] = getAtSlot(battle, lastDamagedBy.slot)
                Unit
            }
        }
        move("tarshot") {
            condition {
                on("Start") {
                    if (pokemon.terastallized != null) return@on false
                    add("-start", pokemon, "Tar Shot")
                    Unit
                }
                on("Effectiveness") {
                    if (move.type != "Fire") return@on Unit
                    val t = targetMon ?: return@on Unit
                    if (source != t.getTypes()[0]) return@on Unit
                    relayInt + 1
                }
            }
        }
        move("dreameater") {
            on("TryImmunity") { pokemon.status == "slp" || pokemon.hasAbility("comatose") }
        }
        move("dive") {
            on("TryMove") {
                val a = pokemon
                val d = sourceMon
                val m = move
                if (a.removeVolatile(m.id)) return@on Unit
                if (a.hasAbility("gulpmissile") && a.species.name == "Cramorant" && !a.transformed) {
                    val forme = if (a.hp <= a.maxhp / 2.0) "cramorantgorging" else "cramorantgulping"
                    a.formeChange(forme, m)
                }
                add("-prepare", a, m.name)
                if (!Js.truthy(battle.runEvent("ChargeMove", a, d, m))) return@on Unit
                a.addVolatile("twoturnmove", d)
                null
            }
            condition {
                on("Immunity") { if (relay == "sandstorm" || relay == "hail") false else Unit }
                on("Invulnerability") { if (move.id in listOf("surf", "whirlpool")) Unit else false }
                on("SourceModifyDamage") { if (move.id == "surf" || move.id == "whirlpool") chainModify(2) else Unit }
            }
        }
        move("mist") {
            condition {
                on("TryBoost") {
                    val b = boosts(relay)
                    val e = sourceEffect
                    val t = pokemon
                    val s = sourceMon
                    if (e != null && e.effectType == "Move" && infiltrates(e) && !t.isAlly(s)) return@on Unit
                    if (s != null && t !== s) {
                        var showMsg = false
                        for (k in b.keys.toList()) {
                            if ((b[k] ?: 0) < 0) {
                                b.remove(k)
                                showMsg = true
                            }
                        }
                        if (showMsg && !hasSecondaries(e)) add("-activate", t, "move: Mist")
                    }
                    Unit
                }
                on("SideStart") { add("-sidestart", target, "Mist"); Unit }
                on("SideEnd") { add("-sideend", target, "Mist"); Unit }
            }
        }
        move("teleport") {
            on("Try") { battle.canSwitch(pokemon.side) != 0 }
        }
        move("roleplay") {
            on("TryHit") {
                val t = pokemon
                val s = sourceMon!!
                if (t.ability == s.ability) return@on false
                if (t.getAbility().flag("failroleplay") || s.getAbility().flag("cantsuppress")) return@on false
                Unit
            }
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val oldAbility = s.setAbility(t.ability)
                if (Js.truthy(oldAbility)) {
                    add("-ability", s, s.getAbility().name, "[from] move: Role Play", "[of] $t")
                    return@on Unit
                }
                oldAbility
            }
        }
        move("magneticflux") {
            on("HitSide") {
                val side = target as Side
                val s = sourceMon!!
                val m = move
                val targets = side.allies().filter {
                    it.hasAbility(listOf("plus", "minus")) && (it.volatiles["maxguard"] == null || Js.truthy(battle.runEvent("TryHit", it, s, m)))
                }
                if (targets.isEmpty()) return@on false
                var didSomething: Any? = false
                for (t in targets) {
                    val r = battle.boost(linkedMapOf("def" to 1, "spd" to 1), t, s, m, false, true)
                    if (Js.truthy(r)) didSomething = r
                }
                didSomething
            }
        }
        move("furycutter") {
            callback("basePowerCallback") {
                val p = pokemon
                if (p.volatiles["furycutter"] == null || move.hit == 1) p.addVolatile("furycutter")
                val multiplier = Js.num(p.volatiles["furycutter"]?.get("multiplier"))
                Js.clampIntRange(Js.number(move.basePower * multiplier), 1, 160)
            }
            condition {
                on("Start") { state["multiplier"] = 1; Unit }
                on("Restart") {
                    if (state.int("multiplier") < 4) state["multiplier"] = state.int("multiplier") shl 1
                    state.duration = 2
                    Unit
                }
            }
        }
        move("magnetrise") {
            on("Try") {
                val src = pokemon
                val t = sourceMon!!
                if (t.volatiles["smackdown"] != null || t.volatiles["ingrain"] != null) return@on false
                if (field.getPseudoWeather("Gravity") != null) {
                    add("cant", src, "move: Gravity", move)
                    return@on null
                }
                Unit
            }
            condition {
                on("Start") { add("-start", target, "Magnet Rise"); Unit }
                on("Immunity") { if (relay == "Ground") false else Unit }
                on("End") { add("-end", target, "Magnet Rise"); Unit }
            }
        }
        move("risingvoltage") {
            callback("basePowerCallback") {
                val s = pokemon
                val t = sourceMon!!
                if (field.isTerrain("electricterrain") && t.isGrounded() == true) {
                    if (!s.isAlly(t)) hint("${move.name}'s BP doubled on grounded target.")
                    move.basePower * 2
                } else move.basePower
            }
        }
        move("fairylock") {
            condition {
                on("FieldStart") { add("-fieldactivate", "move: Fairy Lock"); Unit }
                on("TrapPokemon") { pokemon.tryTrap(); Unit }
            }
        }
        move("powertrick") {
            condition {
                on("Start") {
                    add("-start", pokemon, "Power Trick")
                    swapAtkDef(pokemon)
                    Unit
                }
                on("Copy") { swapAtkDef(pokemon); Unit }
                on("End") {
                    add("-end", pokemon, "Power Trick")
                    swapAtkDef(pokemon)
                    Unit
                }
                on("Restart") { pokemon.removeVolatile("Power Trick"); Unit }
            }
        }
        rampage("thrash")
        rampage("outrage")
        rampage("petaldance")
        move("swallow") {
            on("Try") { pokemon.volatiles["stockpile"] != null }
            on("Hit") {
                val p = pokemon
                val healAmount = doubleArrayOf(0.25, 0.5, 1.0)
                val layers = p.volatiles.getValue("stockpile").int("layers")
                val success = Js.truthy(heal(battle.modify(p.maxhp, healAmount[layers - 1])))
                if (!success) add("-fail", p, "heal")
                p.removeVolatile("stockpile")
                if (success) true else NOT_FAIL
            }
        }
        move("sketch") {
            on("Hit") {
                val t = pokemon
                val s = sourceMon!!
                val m = t.lastMove
                if (s.transformed || m == null || m.id in s.moves) return@on false
                if (Js.truthy(m.data("noSketch")) || Js.truthy(m.isZ) || Js.truthy(m.isMax)) return@on false
                val sketchIndex = s.moves.indexOf("sketch")
                if (sketchIndex < 0) return@on false
                val sketched = MoveSlot(m.name, m.id, m.pp, m.pp, m.target, false, "", false)
                s.moveSlots[sketchIndex] = sketched
                s.baseMoveSlots[sketchIndex] = sketched
                add("-activate", s, "move: Sketch", m.name)
                Unit
            }
        }
        move("snore") {
            on("Try") { pokemon.status == "slp" || pokemon.hasAbility("comatose") }
        }
        move("gmaxbefuddle") {
            self {
                on("Hit") {
                    val src = pokemon
                    for (p in src.foes()) {
                        when (random(3)) {
                            0 -> p.trySetStatus("slp", src)
                            1 -> p.trySetStatus("par", src)
                            else -> p.trySetStatus("psn", src)
                        }
                    }
                    Unit
                }
            }
        }
        move("gmaxchistrike") {
            self {
                on("Hit") {
                    for (p in pokemon.alliesAndSelf()) p.addVolatile("gmaxchistrike")
                    Unit
                }
            }
            condition {
                on("Start") {
                    state["layers"] = 1
                    if ((effect as? EffectLike)?.id !in listOf("costar", "imposter", "psychup", "transform")) {
                        add("-start", target, "move: G-Max Chi Strike")
                    }
                    Unit
                }
                on("Restart") {
                    if (state.int("layers") >= 3) return@on false
                    state["layers"] = state.int("layers") + 1
                    if ((effect as? EffectLike)?.id !in listOf("costar", "imposter", "psychup", "transform")) {
                        add("-start", target, "move: G-Max Chi Strike")
                    }
                    Unit
                }
                on("ModifyCritRatio") { relayInt + state.int("layers") }
            }
        }
        move("gmaxfinale") {
            self {
                on("Hit") {
                    val src = sourceMon!!
                    for (p in src.alliesAndSelf()) battle.heal(p.maxhp / 6.0, p, src, move)
                    Unit
                }
            }
        }
        move("gmaxreplenish") {
            self {
                on("Hit") {
                    if (random(2) == 0) return@on Unit
                    for (p in pokemon.alliesAndSelf()) {
                        if (p.item.isNotEmpty()) continue
                        if (p.lastItem.isNotEmpty() && dex.item(p.lastItem).bool("isBerry")) {
                            val item = p.lastItem
                            p.lastItem = ""
                            add("-item", p, dex.item(item), "[from] move: G-Max Replenish")
                            p.setItem(item)
                        }
                    }
                    Unit
                }
            }
        }
        move("gmaxsmite") {
            self {
                on("Hit") {
                    val src = pokemon
                    for (p in src.foes()) p.addVolatile("confusion", src)
                    Unit
                }
            }
        }
    }

    // region Helpers

    private val COURT_CHANGE = setOf("mist", "lightscreen", "reflect", "spikes", "safeguard", "tailwind", "toxicspikes",
        "stealthrock", "waterpledge", "firepledge", "grasspledge", "stickyweb", "auroraveil", "gmaxsteelsurge",
        "gmaxcannonade", "gmaxvinelash", "gmaxwildfire")

    private fun hook(fn: HookFn): HookFn = fn

    /** `battle.getAtSlot(slot)`. */
    fun getAtSlot(battle: Battle, slot: String): Pokemon? {
        val side = battle.sides[slot[1].code - 49]
        val position = slot[2].code - 97
        val positionOffset = (side.n / 2) * side.active.size
        return side.active.getOrNull(position - positionOffset)
    }

    private fun infiltrates(effect: EffectLike): Boolean =
        if (effect is ActiveMove) effect.infiltrates else Js.truthy(effect.data("infiltrates"))

    private fun hasSecondaries(effect: EffectLike?): Boolean = when (effect) {
        null -> false
        is ActiveMove -> effect.secondaries != null
        else -> effect.data("secondaries") != null
    }

    private fun swapAtkDef(p: Pokemon) {
        val newatk = p.storedStats.getValue("def")
        val newdef = p.storedStats.getValue("atk")
        p.storedStats["atk"] = newatk
        p.storedStats["def"] = newdef
    }

    private fun HookRegistrar.crashMove(id: String, conditionName: String) = move(id) {
        on("MoveFail") {
            val src = sourceMon!!
            damage(src.baseMaxhp / 2.0, src, src, dex.condition(conditionName))
            Unit
        }
    }

    private fun HookRegistrar.hpScaled(id: String) = move(id) {
        callback("basePowerCallback") { Js.number(move.basePower.toDouble() * pokemon.hp / pokemon.maxhp) }
    }

    private fun HookRegistrar.rampage(id: String) = move(id) {
        on("AfterMove") {
            val p = pokemon
            if (p.volatiles["lockedmove"] != null && p.volatiles["lockedmove"]!!.duration == 1) p.removeVolatile("lockedmove")
            Unit
        }
    }

    /** Sky Attack, Freeze Shock, Ice Burn: charge on the first turn, strike on the second. */
    private fun HookRegistrar.chargeMove(id: String) = move(id) {
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

    private fun HookRegistrar.room(id: String, name: String, body: jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks.() -> Unit) = move(id) {
        condition {
            callback("durationCallback") {
                val s = target as? Pokemon
                if (s?.hasAbility("persistent") == true) {
                    add("-activate", s, "ability: Persistent", "[move] $name")
                    7
                } else 5
            }
            on("FieldRestart") { field.removePseudoWeather(id); Unit }
            body()
        }
    }

    private fun weatherHeal(call: HookCall): Any? = with(call) {
        val p = pokemon
        val factor = when (p.effectiveWeather()) {
            "sunnyday", "desolateland" -> 0.667
            "raindance", "primordialsea", "sandstorm", "hail", "snow" -> 0.25
            else -> 0.5
        }
        val success = Js.truthy(heal(battle.modify(p.maxhp, factor)))
        if (!success) {
            add("-fail", p, "heal")
            return@with NOT_FAIL
        }
        success
    }

    private fun HookRegistrar.itemSwap(id: String, from: String) = move(id) {
        on("TryImmunity") { !pokemon.hasAbility("stickyhold") }
        on("Hit") {
            val t = pokemon
            val s = sourceMon!!
            val m = move
            val yourItem = t.takeItem(s) as? Effect
            val myItem = s.takeItem() as? Effect
            if (t.item.isNotEmpty() || s.item.isNotEmpty() || (yourItem == null && myItem == null)) {
                if (yourItem != null) t.item = yourItem.id
                if (myItem != null) s.item = myItem.id
                return@on false
            }
            if ((myItem != null && !Js.truthy(battle.singleEvent("TakeItem", myItem, s.itemState, t, s, m, myItem))) ||
                (yourItem != null && !Js.truthy(battle.singleEvent("TakeItem", yourItem, t.itemState, s, t, m, yourItem)))) {
                if (yourItem != null) t.item = yourItem.id
                if (myItem != null) s.item = myItem.id
                return@on false
            }
            add("-activate", s, "move: Trick", "[of] $t")
            if (myItem != null) {
                t.setItem(myItem.id)
                add("-item", t, myItem, "[from] $from")
            } else {
                add("-enditem", t, yourItem, "[silent]", "[from] $from")
            }
            if (yourItem != null) {
                s.setItem(yourItem.id)
                add("-item", s, yourItem, "[from] $from")
            } else {
                add("-enditem", s, myItem, "[silent]", "[from] $from")
            }
            Unit
        }
    }

    private fun ceaselessEdge(call: HookCall, condition: String) = with(call) {
        val src = sourceMon!!
        if (!move.hasSheerForce && src.hp != 0) {
            for (side in src.side.foeSidesWithConditions()) side.addSideCondition(condition)
        }
    }

    private fun HookRegistrar.stealEat(id: String, moveTag: String) = move(id) {
        on("Hit") {
            val t = pokemon
            val s = sourceMon!!
            val item = t.getItem()
            if (s.hp != 0 && item.bool("isBerry") && Js.truthy(t.takeItem(s))) {
                add("-enditem", t, item.name, "[from] stealeat", moveTag, "[of] $s")
                if (Js.truthy(battle.singleEvent("Eat", item, null, s, null, null))) {
                    battle.runEvent("EatItem", s, null, null, item)
                    if (item.id == "leppaberry") t.staleness = "external"
                }
                if (Js.truthy(item.handler("onEat"))) s.ateBerry = true
            }
            Unit
        }
    }

    /** The shared `onPrepareHit` of the Pledge moves: wait for an ally's combo Pledge. */
    fun pledgePrepareHit(call: HookCall, partners: List<String>): Any? = with(call) {
        val src = sourceMon!!
        for (action in battle.queue.list.toList()) {
            val actor = action.pokemon
            if (action.move == null || actor?.isActive != true || actor.fainted || action.maxMove != null) continue
            if (actor.isAlly(src) && action.move!!.id in partners) {
                battle.queue.prioritizeAction(action, move)
                add("-waiting", src, actor)
                return@with null
            }
        }
        Unit
    }

    /** Future Sight's hit, `new Move(moveData)`: plain data without the move's handlers. */
    private fun futureMoveData(battle: Battle): ActiveMove {
        val raw = JsonObject().apply {
            addProperty("name", "Future Sight")
            addProperty("accuracy", 100)
            addProperty("basePower", 120)
            addProperty("category", "Special")
            addProperty("priority", 0)
            add("flags", JsonObject().apply {
                addProperty("allyanim", 1)
                addProperty("metronome", 1)
                addProperty("futuremove", 1)
            })
            addProperty("ignoreImmunity", false)
            addProperty("type", "Psychic")
        }
        return ActiveMove(MoveData("futuresight", raw, ""))
    }

    /** Held items whose `fling.effect` runs on the target instead of a status. */
    private fun flingEffect(itemId: String): HookFn = when (itemId) {
        "mentalherb" -> hook {
            val p = pokemon
            val conditions = listOf("attract", "taunt", "encore", "torment", "disable", "healblock")
            for (first in conditions) {
                if (p.volatiles[first] != null) {
                    for (second in conditions) {
                        p.removeVolatile(second)
                        if (first == "attract" && second == "attract") add("-end", p, "move: Attract", "[from] item: Mental Herb")
                    }
                    return@hook Unit
                }
            }
            Unit
        }
        "whiteherb" -> hook {
            val p = pokemon
            var activate = false
            val b = LinkedHashMap<String, Int>()
            for ((i, v) in p.boosts) {
                if (v < 0) {
                    activate = true
                    b[i] = 0
                }
            }
            if (activate) {
                p.setBoost(b)
                add("-clearnegativeboost", p, "[silent]")
            }
            Unit
        }
        else -> hook { battle.missingHooks.add("item:$itemId.fling.effect"); Unit }
    }

    // endregion
}

/** A hit block a handler builds on the fly with any of the fields Showdown's plain objects may carry. */
class PlainHit(
    override val hitBoosts: Map<String, Int>? = null,
    override val chance: Int? = null,
    override val hitStatus: String? = null,
    override val hitVolatileStatus: String? = null,
    override val hitSideCondition: String? = null,
    override val hitSlotCondition: String? = null,
    override val hitPseudoWeather: String? = null,
    override val hitSelf: HitData? = null,
) : HitData {
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
    override val hitHeal: IntArray? get() = null
    override val hitForceStatus: String? get() = null
    override val hitWeather: String? get() = null
    override val hitTerrain: String? get() = null
    override val hitForceSwitch: Boolean get() = false
    override val hitSelfdestruct: String? get() = null
    override val hitSelfSwitch: Any? get() = null
    override val hitSecondaries: List<HitData>? get() = null
    override val hitAbility: EffectLike? get() = null
}
