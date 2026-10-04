package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.blockStatus
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.boosts
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.cureOnUpdate
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.hasSecondaries
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.statusOf
import jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.Prioritized

/** Abilities from `data/abilities.js`, ported in usage order (second part). */
object PortAbilitiesA2 : HookSet() {
    private val HookCall.holder: Pokemon get() = state.target as Pokemon

    /** A Pokémon in `speedSort`: no order or priority, only speed. */
    private class SpeedMon(val pokemon: Pokemon) : Prioritized {
        override val sortOrder: Int? get() = null
        override val sortPriority: Double get() = 0.0
        override val sortSpeed: Double get() = pokemon.speed.toDouble()
        override val sortSubOrder: Int get() = 0
    }

    private val SUN = listOf("sunnyday", "desolateland")

    override fun HookRegistrar.define() {
        ability("thickfat") {
            on("SourceModifyAtk") { if (move.type == "Ice" || move.type == "Fire") chainModify(0.5) else Unit }
            on("SourceModifySpA") { if (move.type == "Ice" || move.type == "Fire") chainModify(0.5) else Unit }
        }
        ability("adaptability") {
            on("ModifySTAB") {
                val m = move
                if (m.forceSTAB || pokemon.hasType(m.type)) {
                    if (relayNum == 2.0) return@on 2.25
                    return@on 2
                }
                Unit
            }
        }
        ability("justified") {
            on("DamagingHit") { if (move.type == "Dark") boost(mapOf("atk" to 1)); Unit }
        }
        ability("stamina") {
            on("DamagingHit") { boost(mapOf("def" to 1)); Unit }
        }
        contactStatus("static", "par")
        contactStatus("poisonpoint", "psn")
        ability("telepathy") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && t.isAlly(sourceMon) && move.category != "Status") {
                    add("-activate", t, "ability: Telepathy")
                    null
                } else Unit
            }
        }
        ability("cursedbody") {
            on("DamagingHit") {
                val src = sourceMon!!
                val m = move
                if (src.volatiles["disable"] != null) return@on Unit
                if (!Js.truthy(m.isMax) && !m.flag("futuremove") && m.id != "struggle") {
                    if (randomChance(3, 10)) src.addVolatile("disable", state.target as Pokemon)
                }
                Unit
            }
        }
        ability("intrepidsword") {
            on("Start") {
                val p = pokemon
                if (p.swordBoost) return@on Unit
                p.swordBoost = true
                battle.boost(mapOf("atk" to 1), p)
                Unit
            }
        }
        ability("unseenfist") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.flag("contact")) m.flags.remove("protect")
                Unit
            }
        }
        ability("magician") {
            on("AfterMoveSecondarySelf") {
                val src = pokemon
                val t = sourceMon
                val m = activeMove
                if (m == null || t == null || src.switchFlag == true) return@on Unit
                if (t !== src && m.category != "Status") {
                    if (src.item.isNotEmpty() || src.volatiles["gem"] != null || m.id == "fling") return@on Unit
                    val yourItem = t.takeItem(src) as? EffectLike ?: return@on Unit
                    if (!src.setItem(yourItem.id)) {
                        t.item = yourItem.id
                        return@on Unit
                    }
                    add("-item", src, yourItem, "[from] ability: Magician", "[of] $t")
                }
                Unit
            }
        }
        ability("skilllink") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                val hits = m.multihit
                if (hits is List<*> && hits.isNotEmpty()) m.multihit = hits[1]
                if (m.multiaccuracy) m.multiaccuracy = false
                Unit
            }
        }
        ability("hydration") {
            on("Residual") {
                val p = pokemon
                if (p.status.isNotEmpty() && p.effectiveWeather() in listOf("raindance", "primordialsea")) {
                    add("-activate", p, "ability: Hydration")
                    p.cureStatus()
                }
                Unit
            }
        }
        ability("scrappy") {
            on("ModifyMove") { ignoreGhostImmunity(relay as ActiveMove); Unit }
            on("TryBoost") {
                val b = boosts(relay)
                if (sourceEffect?.name == "Intimidate" && (b["atk"] ?: 0) != 0) {
                    b.remove("atk")
                    add("-fail", target, "unboost", "Attack", "[from] ability: Scrappy", "[of] $target")
                }
                Unit
            }
        }
        ability("mindseye") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                m.ignoreEvasion = true
                ignoreGhostImmunity(m)
                Unit
            }
        }
        ability("illuminate") {
            on("ModifyMove") { (relay as ActiveMove).ignoreEvasion = true; Unit }
        }
        ability("galewings") {
            on("ModifyPriority") {
                val p = pokemon
                if (activeMove?.type == "Flying" && p.hp == p.maxhp) Js.number(relayNum + 1) else Unit
            }
        }
        ability("neutralizinggas") {
            on("PreStart") {
                val p = pokemon
                add("-ability", p, "Neutralizing Gas")
                p.abilityState["ending"] = false
                for (t in battle.getAllActive()) {
                    if (t.species.id == "xerneas" && t.ability == "fairyaura" && !t.transformed) t.formeChange("xerneasactive", null)
                }
                val strongWeathers = listOf("desolateland", "primordialsea", "deltastream")
                for (t in battle.getAllActive()) {
                    if (t.hasItem("Ability Shield")) {
                        add("-block", t, "item: Ability Shield")
                        continue
                    }
                    if (t.volatiles["commanding"] != null) continue
                    if (t.illusion != null) {
                        battle.singleEvent("End", dex.ability("Illusion"), t.abilityState, t, p, "neutralizinggas")
                    }
                    if (t.volatiles["slowstart"] != null) {
                        t.volatiles.remove("slowstart")
                        add("-end", t, "Slow Start", "[silent]")
                    }
                    if (t.getAbility().id in strongWeathers) {
                        battle.singleEvent("End", dex.ability(t.getAbility().id), t.abilityState, t, p, "neutralizinggas")
                    }
                }
                Unit
            }
            on("AnySwitchIn") {
                val t = pokemon
                if (t.species.id == "xerneas" && t.ability == "fairyaura" && !t.transformed) t.formeChange("xerneasactive", null)
                Unit
            }
            on("End") {
                val src = pokemon
                if (src.transformed) return@on Unit
                for (p in battle.getAllActive()) {
                    if (p !== src && p.hasAbility("Neutralizing Gas")) return@on Unit
                }
                add("-end", src, "ability: Neutralizing Gas")
                if (Js.truthy(src.abilityState["ending"])) return@on Unit
                src.abilityState["ending"] = true
                val sortedActive = battle.getAllActive().map { SpeedMon(it) }.toMutableList()
                battle.speedSort(sortedActive)
                for (entry in sortedActive) {
                    val p = entry.pokemon
                    if (p !== src) {
                        if (p.getAbility().flag("cantsuppress")) continue
                        if (p.hasItem("abilityshield")) continue
                        battle.singleEvent("Start", p.getAbility(), p.abilityState, p)
                        if (p.ability == "gluttony") p.abilityState["gluttony"] = false
                    }
                }
                Unit
            }
        }
        ability("aftermath") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (t.hp == 0 && battle.checkMoveMakesContact(move, src, t, true)) battle.damage(src.baseMaxhp / 4.0, src, t)
                Unit
            }
        }
        ability("angerpoint") {
            on("Hit") {
                val t = pokemon
                if (t.hp == 0) return@on Unit
                val m = activeMove
                if (m?.effectType == "Move" && t.getMoveHitData(m).crit) battle.boost(mapOf("atk" to 12), t, t)
                Unit
            }
        }
        weatherLock("airlock", "Air Lock")
        weatherLock("cloudnine", "Cloud Nine")
        ability("baddreams") {
            on("Residual") {
                val p = pokemon
                if (p.hp == 0) return@on Unit
                for (t in p.foes()) {
                    if (t.status == "slp" || t.hasAbility("comatose")) battle.damage(t.baseMaxhp / 8.0, t, p)
                }
                Unit
            }
        }
        halfHpTrigger("berserk", "checkedBerserk", linkedMapOf("spa" to 1), smartTargetAware = true)
        halfHpTrigger("angershell", "checkedAngerShell",
            linkedMapOf("atk" to 1, "spa" to 1, "spe" to 1, "def" to -1, "spd" to -1), smartTargetAware = false)
        ability("goodasgold") {
            on("TryHit") {
                val t = pokemon
                if (move.category == "Status" && t !== source) {
                    add("-immune", t, "[from] ability: Good as Gold")
                    null
                } else Unit
            }
        }
        ability("hadronengine") {
            on("Start") {
                if (!Js.truthy(field.setTerrain("electricterrain")) && field.isTerrain("electricterrain")) {
                    add("-activate", pokemon, "ability: Hadron Engine")
                }
                Unit
            }
            on("ModifySpA") { if (field.isTerrain("electricterrain")) chainModify(intArrayOf(5461, 4096)) else Unit }
        }
        ability("poisonpuppeteer") {
            on("AnyAfterSetStatus") {
                val src = sourceMon!!
                val t = pokemon
                if (src.baseSpecies.name != "Pecharunt") return@on Unit
                if (src !== state.target || t === src || sourceEffect?.effectType != "Move") return@on Unit
                val id = (relay as EffectLike).id
                if (id == "psn" || id == "tox") t.addVolatile("confusion")
                Unit
            }
        }
        ability("soulheart") {
            on("AnyFaint") { battle.boost(mapOf("spa" to 1), state.target as Pokemon); Unit }
        }
        ability("terashift") {
            on("PreStart") {
                val p = pokemon
                if (p.baseSpecies.baseSpecies != "Terapagos") return@on Unit
                if (p.species.forme != "Terastal") {
                    add("-activate", p, "ability: Tera Shift")
                    p.formeChange("Terapagos-Terastal", self, true)
                    p.baseMaxhp = recalcMaxhp(p)
                    val newMaxHP = p.baseMaxhp
                    p.hp = newMaxHP - (p.maxhp - p.hp)
                    p.maxhp = newMaxHP
                    add("-heal", p, p.getHealth, "[silent]")
                }
                Unit
            }
        }
        ability("friendguard") {
            on("AnyModifyDamage") {
                val t = sourceMon ?: return@on Unit
                if (t !== state.target && t.isAlly(state.target as Pokemon)) chainModify(0.75) else Unit
            }
        }
        ability("rockhead") {
            on("Damage") {
                if (sourceEffect?.id == "recoil") {
                    val active = battle.activeMove ?: error("Battle.activeMove is null")
                    if (active.id != "struggle") return@on null
                }
                Unit
            }
        }
        ability("electricsurge") {
            on("Start") { field.setTerrain("electricterrain"); Unit }
        }
        ability("analytic") {
            on("BasePower") {
                val p = pokemon
                var boosted = true
                for (t in battle.getAllActive()) {
                    if (t === p) continue
                    if (battle.queue.willMove(t) != null) {
                        boosted = false
                        break
                    }
                }
                if (boosted) chainModify(intArrayOf(5325, 4096)) else Unit
            }
        }
        ability("dryskin") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.type == "Water") {
                    if (!Js.truthy(battle.heal(t.baseMaxhp / 4.0))) add("-immune", t, "[from] ability: Dry Skin")
                    null
                } else Unit
            }
            on("SourceBasePower") { if (move.type == "Fire") chainModify(1.25) else Unit }
            on("Weather") {
                val t = pokemon
                if (t.hasItem("utilityumbrella")) return@on Unit
                val id = sourceEffect?.id
                if (id == "raindance" || id == "primordialsea") heal(t.baseMaxhp / 8.0)
                else if (id == "sunnyday" || id == "desolateland") battle.damage(t.baseMaxhp / 8.0, t, t)
                Unit
            }
        }
        ability("purifyingsalt") {
            on("SetStatus") {
                if (Js.truthy(statusOf(effect))) add("-immune", target, "[from] ability: Purifying Salt")
                false
            }
            on("TryAddVolatile") {
                if ((relay as EffectLike).id == "yawn") {
                    add("-immune", target, "[from] ability: Purifying Salt")
                    null
                } else Unit
            }
            on("SourceModifyAtk") { if (move.type == "Ghost") chainModify(0.5) else Unit }
            on("SourceModifySpA") { if (move.type == "Ghost") chainModify(0.5) else Unit }
        }
        ability("bulletproof") {
            on("TryHit") {
                if (move.flag("bullet")) {
                    add("-immune", target, "[from] ability: Bulletproof")
                    null
                } else Unit
            }
        }
        ability("punkrock") {
            on("BasePower") { if (move.flag("sound")) chainModify(intArrayOf(5325, 4096)) else Unit }
            on("SourceModifyDamage") { if (move.flag("sound")) chainModify(0.5) else Unit }
        }
        ability("hugepower") {
            on("ModifyAtk") { chainModify(2) }
        }
        ability("waterbubble") {
            on("SourceModifyAtk") { if (move.type == "Fire") chainModify(0.5) else Unit }
            on("SourceModifySpA") { if (move.type == "Fire") chainModify(0.5) else Unit }
            on("ModifyAtk") { if (move.type == "Water") chainModify(2) else Unit }
            on("ModifySpA") { if (move.type == "Water") chainModify(2) else Unit }
            cureOnUpdate(this, "Water Bubble", listOf("brn"))
            blockStatus(this, "Water Bubble", listOf("brn"))
        }
        ability("immunity") {
            cureOnUpdate(this, "Immunity", listOf("psn", "tox"))
            blockStatus(this, "Immunity", listOf("psn", "tox"))
        }
        ability("magmaarmor") {
            cureOnUpdate(this, "Magma Armor", listOf("frz"))
            on("Immunity") { if (relay == "frz") false else Unit }
        }
        ability("download") {
            on("Start") {
                val p = pokemon
                var totaldef = 0
                var totalspd = 0
                for (t in p.foes()) {
                    totaldef += t.getStat("def", false, true)
                    totalspd += t.getStat("spd", false, true)
                }
                if (totaldef != 0 && totaldef >= totalspd) boost(mapOf("spa" to 1))
                else if (totalspd != 0) boost(mapOf("atk" to 1))
                Unit
            }
        }
        ability("damp") {
            on("AnyTryMove") {
                val e = sourceEffect
                if (e?.id in listOf("explosion", "mindblown", "mistyexplosion", "selfdestruct")) {
                    battle.attrLastMove("[still]")
                    add("cant", state.target, "ability: Damp", e, "[of] $target")
                    return@on false
                }
                Unit
            }
            on("AnyDamage") { if (sourceEffect?.name == "Aftermath") false else Unit }
        }
        ability("quickdraw") {
            on("FractionalPriority") {
                if (move.category != "Status" && randomChance(3, 10)) {
                    add("-activate", pokemon, "ability: Quick Draw")
                    0.1
                } else Unit
            }
        }
        ability("flowerveil") {
            on("AllyTryBoost") {
                val t = pokemon
                if ((source != null && t === source) || !t.hasType("Grass")) return@on Unit
                val b = boosts(relay)
                var showMsg = false
                for (key in b.keys.toList()) {
                    if ((b[key] ?: 0) < 0) {
                        b.remove(key)
                        showMsg = true
                    }
                }
                if (showMsg && !hasSecondaries(effect)) add("-block", t, "ability: Flower Veil", "[of] ${state.target}")
                Unit
            }
            on("AllySetStatus") {
                val t = pokemon
                val e = sourceEffect
                if (t.hasType("Grass") && source != null && t !== source && e != null && e.id != "yawn") {
                    if (e.name == "Synchronize" || (e.effectType == "Move" && !hasSecondaries(e))) {
                        add("-block", t, "ability: Flower Veil", "[of] ${state.target}")
                    }
                    return@on null
                }
                Unit
            }
            on("AllyTryAddVolatile") {
                val t = pokemon
                if (t.hasType("Grass") && (relay as EffectLike).id == "yawn") {
                    add("-block", t, "ability: Flower Veil", "[of] ${state.target}")
                    null
                } else Unit
            }
        }
        ability("pickpocket") {
            on("AfterMoveSecondary") {
                val t = pokemon
                val src = sourceMon
                if (src != null && src !== t && activeMove?.flag("contact") == true) {
                    if (t.item.isNotEmpty() || Js.truthy(t.switchFlag) || t.forceSwitchFlag || src.switchFlag == true) return@on Unit
                    val yourItem = src.takeItem(t) as? EffectLike ?: return@on Unit
                    if (!t.setItem(yourItem.id)) {
                        src.item = yourItem.id
                        return@on Unit
                    }
                    add("-enditem", src, yourItem, "[silent]", "[from] ability: Pickpocket", "[of] $src")
                    add("-item", t, yourItem, "[from] ability: Pickpocket", "[of] $src")
                }
                Unit
            }
        }
        ability("stakeout") {
            on("ModifyAtk") { val d = sourceMon ?: return@on Unit; if (d.activeTurns == 0) chainModify(2) else Unit }
            on("ModifySpA") { val d = sourceMon ?: return@on Unit; if (d.activeTurns == 0) chainModify(2) else Unit }
        }
        ability("toughclaws") {
            on("BasePower") { if (move.flag("contact")) chainModify(intArrayOf(5325, 4096)) else Unit }
        }
        ability("shieldsdown") {
            on("Start") {
                val p = pokemon
                if (p.hp > p.maxhp / 2.0 && p.species.id == "minior") p.formeChange("Minior-Meteor")
                Unit
            }
            on("Residual") {
                val p = pokemon
                if (p.hp > p.maxhp / 2.0 && p.species.id == "minior") p.formeChange("Minior-Meteor")
                else if (p.hp < p.maxhp / 2.0 && p.species.id == "miniormeteor") p.formeChange("Minior")
                Unit
            }
            on("SetStatus") {
                val t = pokemon
                if (t.species.id != "miniormeteor" || t.transformed) return@on Unit
                if (Js.truthy(statusOf(effect))) add("-immune", t, "[from] ability: Shields Down")
                false
            }
            on("TryAddVolatile") {
                val t = pokemon
                if (t.species.id != "miniormeteor" || t.transformed) return@on Unit
                if ((relay as EffectLike).id != "yawn") return@on Unit
                add("-immune", t, "[from] ability: Shields Down")
                null
            }
        }
        ability("filter") {
            on("SourceModifyDamage") { if ((source as Pokemon).getMoveHitData(move).typeMod > 0) chainModify(0.75) else Unit }
        }
        ability("hospitality") {
            on("Start") {
                val p = pokemon
                for (ally in p.adjacentAllies()) battle.heal(ally.baseMaxhp / 4.0, ally, p)
                Unit
            }
        }
        ability("powerofalchemy") {
            on("AllyFaint") {
                val h = state.target as Pokemon
                val t = pokemon
                if (h.hp == 0) return@on Unit
                val ability = t.getAbility()
                if (ability.flag("noreceiver") || ability.id == "noability") return@on Unit
                if (Js.truthy(h.setAbility(ability.id))) add("-ability", h, ability, "[from] ability: Power of Alchemy", "[of] $t")
                Unit
            }
        }
        ability("longreach") {
            on("ModifyMove") { (relay as ActiveMove).flags.remove("contact"); Unit }
        }
        ability("quickfeet") {
            on("ModifySpe") { if (pokemon.status.isNotEmpty()) chainModify(1.5) else Unit }
        }
        ability("supremeoverlord") {
            on("Start") {
                val p = pokemon
                if (p.side.totalFainted != 0) {
                    add("-activate", p, "ability: Supreme Overlord")
                    val fallen = minOf(p.side.totalFainted, 5)
                    add("-start", p, "fallen$fallen", "[silent]")
                    state["fallen"] = fallen
                }
                Unit
            }
            on("End") {
                val fallen = if (state.has("fallen")) Js.str(state["fallen"]) else "undefined"
                add("-end", pokemon, "fallen$fallen", "[silent]")
                Unit
            }
            on("BasePower") {
                if (Js.truthy(state["fallen"])) {
                    val powMod = intArrayOf(4096, 4506, 4915, 5325, 5734, 6144)
                    chainModify(powMod[state.int("fallen")], 4096)
                } else Unit
            }
        }
        ability("cutecharm") {
            on("DamagingHit") {
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, pokemon)) {
                    if (randomChance(3, 10)) src.addVolatile("attract", state.target as Pokemon)
                }
                Unit
            }
        }
        ability("sandspit") {
            on("DamagingHit") { field.setWeather("sandstorm"); Unit }
        }
        ability("superluck") {
            on("ModifyCritRatio") { Js.number(relayNum + 1) }
        }
        ability("windpower") {
            on("DamagingHit") {
                if (move.flag("wind")) pokemon.addVolatile("charge")
                Unit
            }
            on("AllySideConditionStart") {
                if ((effect as EffectLike).id == "tailwind") holder.addVolatile("charge")
                Unit
            }
        }
        ability("sandforce") {
            on("BasePower") {
                if (field.isWeather("sandstorm")) {
                    val type = move.type
                    if (type == "Rock" || type == "Ground" || type == "Steel") return@on chainModify(intArrayOf(5325, 4096))
                }
                Unit
            }
            on("Immunity") { if (relay == "sandstorm") false else Unit }
        }
        ability("minus") {
            on("ModifySpA") {
                for (allyActive in pokemon.allies()) {
                    if (allyActive.hasAbility(listOf("minus", "plus"))) return@on chainModify(1.5)
                }
                Unit
            }
        }
        ability("cudchew") {
            on("EatItem") {
                val item = relay as Effect
                val p = pokemon
                if (item.bool("isBerry") && Js.truthy(p.addVolatile("cudchew"))) p.volatiles["cudchew"]!!["berry"] = item
                Unit
            }
            on("End") { pokemon.volatiles.remove("cudchew"); Unit }
            condition {
                on("Restart") { state.duration = 2; Unit }
                on("End") {
                    val p = pokemon
                    if (p.hp != 0) {
                        val item = state["berry"] as Effect
                        add("-activate", p, "ability: Cud Chew")
                        add("-enditem", p, item.name, "[eat]")
                        if (Js.truthy(battle.singleEvent("Eat", item, null, p, null, null))) {
                            battle.runEvent("EatItem", p, null, null, item)
                        }
                        if (item.declares("onEat")) p.ateBerry = true
                    }
                    Unit
                }
            }
        }
        ability("whitesmoke") {
            on("TryBoost") { TopAbilities.clearBodyTryBoost(this, "[from] ability: White Smoke") }
        }
        ability("tangledfeet") {
            on("ModifyAccuracy") {
                if (!Js.isNumber(relay)) return@on Unit
                if (targetMon?.volatiles?.get("confusion") != null) chainModify(0.5) else Unit
            }
        }
        ability("battlebond") {
            on("SourceAfterFaint") {
                if (sourceEffect?.effectType != "Move") return@on Unit
                val src = sourceMon!!
                if (Js.truthy(src.abilityState["battleBondTriggered"])) return@on Unit
                if (src.species.id == "greninjabond" && src.hp != 0 && !src.transformed && src.side.foePokemonLeft() != 0 &&
                    src.happiness >= 250) {
                    src.formeChange("Greninja-Ash", self, true, "[msg]")
                    src.abilityState["battleBondTriggered"] = true
                }
                if (src.species.id == "greninjabond" && src.hp != 0 && !src.transformed && src.side.foePokemonLeft() != 0 &&
                    src.happiness < 250) {
                    battle.boost(linkedMapOf("atk" to 1, "spa" to 1, "spe" to 1), src, src, self)
                    add("-activate", src, "ability: Battle Bond")
                    src.abilityState["battleBondTriggered"] = true
                }
                Unit
            }
        }
        ability("comatose") {
            on("Start") { add("-ability", pokemon, "Comatose"); Unit }
            on("SetStatus") {
                if (Js.truthy(statusOf(effect))) add("-immune", target, "[from] ability: Comatose")
                false
            }
        }
        ability("defeatist") {
            on("ModifyAtk") { if (pokemon.hp <= pokemon.maxhp / 2.0) chainModify(0.5) else Unit }
            on("ModifySpA") { if (pokemon.hp <= pokemon.maxhp / 2.0) chainModify(0.5) else Unit }
        }
        ability("flowergift") {
            on("Start") { battle.singleEvent("WeatherChange", self, state, pokemon); Unit }
            on("WeatherChange") {
                val p = pokemon
                if (!p.isActive || p.baseSpecies.baseSpecies != "Cherrim" || p.transformed) return@on Unit
                if (p.hp == 0) return@on Unit
                if (p.effectiveWeather() in SUN) {
                    if (p.species.id != "cherrimsunshine") p.formeChange("Cherrim-Sunshine", self, true, "[msg]")
                } else {
                    if (p.species.id == "cherrimsunshine") p.formeChange("Cherrim", self, true, "[msg]")
                }
                Unit
            }
            on("AllyModifyAtk") {
                if (holder.baseSpecies.baseSpecies != "Cherrim") return@on Unit
                if (pokemon.effectiveWeather() in SUN) chainModify(1.5) else Unit
            }
            on("AllyModifySpD") {
                if (holder.baseSpecies.baseSpecies != "Cherrim") return@on Unit
                if (pokemon.effectiveWeather() in SUN) chainModify(1.5) else Unit
            }
        }
        ability("gorillatactics") {
            on("Start") { pokemon.abilityState["choiceLock"] = ""; Unit }
            on("BeforeMove") {
                val p = pokemon
                val m = move
                if (m.isZOrMaxPowered || m.id == "struggle") return@on Unit
                val lock = p.abilityState["choiceLock"]
                if (Js.truthy(lock) && lock != m.id) {
                    battle.addMove("move", p, m.name)
                    battle.attrLastMove("[still]")
                    add("-fail", p)
                    return@on false
                }
                Unit
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                val p = pokemon
                if (Js.truthy(p.abilityState["choiceLock"]) || m.isZOrMaxPowered || m.id == "struggle") return@on Unit
                p.abilityState["choiceLock"] = m.id
                Unit
            }
            on("ModifyAtk") { if (pokemon.volatiles["dynamax"] != null) Unit else chainModify(1.5) }
            on("DisableMove") {
                val p = pokemon
                val lock = p.abilityState["choiceLock"]
                if (!Js.truthy(lock)) return@on Unit
                if (p.volatiles["dynamax"] != null) return@on Unit
                for (moveSlot in p.moveSlots) {
                    if (moveSlot.id != lock) p.disableMove(moveSlot.id, false, state.sourceEffect)
                }
                Unit
            }
            on("End") { pokemon.abilityState["choiceLock"] = ""; Unit }
        }
        ability("mimicry") {
            on("Start") { battle.singleEvent("TerrainChange", self, state, pokemon); Unit }
            on("TerrainChange") {
                val p = pokemon
                val types: List<String> = when (field.terrain) {
                    "electricterrain" -> listOf("Electric")
                    "grassyterrain" -> listOf("Grass")
                    "mistyterrain" -> listOf("Fairy")
                    "psychicterrain" -> listOf("Psychic")
                    else -> p.baseSpecies.types
                }
                val oldTypes = p.getTypes()
                if (oldTypes.joinToString(",") == types.joinToString(",") || !p.setType(types)) return@on Unit
                if (field.terrain.isNotEmpty() || p.transformed) {
                    add("-start", p, "typechange", types.joinToString("/"), "[from] ability: Mimicry")
                    if (field.terrain.isEmpty()) hint("Transform Mimicry changes you to your original un-transformed types.")
                } else {
                    add("-activate", p, "ability: Mimicry")
                    add("-end", p, "typechange", "[silent]")
                }
                Unit
            }
        }
        ability("normalize") {
            on("ModifyType") {
                val m = relay as ActiveMove
                val noModifyType = listOf("hiddenpower", "judgment", "multiattack", "naturalgift", "revelationdance", "struggle",
                    "technoblast", "terrainpulse", "weatherball")
                if (!(Js.truthy(m.isZ) && m.category != "Status") && m.id !in noModifyType &&
                    !(m.name == "Tera Blast" && pokemon.terastallized != null)) {
                    m.type = "Normal"
                    m.typeChangerBoosted = self
                }
                Unit
            }
            on("BasePower") { if (move.typeChangerBoosted === self) chainModify(intArrayOf(4915, 4096)) else Unit }
        }
        ability("perishbody") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (!battle.checkMoveMakesContact(move, src, t)) return@on Unit
                var announced = false
                for (p in listOf(t, src)) {
                    if (p.volatiles["perishsong"] != null) continue
                    if (!announced) {
                        add("-ability", t, "Perish Body")
                        announced = true
                    }
                    p.addVolatile("perishsong")
                }
                Unit
            }
        }
        ability("schooling") {
            on("Start") { schooling(pokemon, checkHp = false); Unit }
            on("Residual") { schooling(pokemon, checkHp = true); Unit }
        }
        ability("spicyspray") {
            on("DamagingHit") { sourceMon!!.trySetStatus("brn", pokemon); Unit }
        }
        ability("victorystar") {
            on("AnyModifyAccuracy") {
                if (sourceMon!!.isAlly(state.target as Pokemon) && Js.isNumber(relay)) chainModify(intArrayOf(4506, 4096)) else Unit
            }
        }
        ability("wonderguard") {
            on("TryHit") {
                val t = pokemon
                val src = sourceMon!!
                val m = move
                if (t === src || m.category == "Status" || m.type == "???" || m.id == "struggle") return@on Unit
                if (m.id == "skydrop" && src.volatiles["skydrop"] == null) return@on Unit
                if (t.runEffectiveness(m) <= 0) {
                    if (m.smartTarget == true) m.smartTarget = false
                    else add("-immune", t, "[from] ability: Wonder Guard")
                    return@on null
                }
                Unit
            }
        }
    }

    // region Shared shapes

    /** Scrappy and Mind's Eye: `move.ignoreImmunity` gains Fighting and Normal (a fresh map, never the dex's). */
    private fun ignoreGhostImmunity(m: ActiveMove) {
        if (!Js.truthy(m.ignoreImmunity)) m.ignoreImmunity = LinkedHashMap<String, Any?>()
        val current = m.ignoreImmunity
        if (current != true) {
            @Suppress("UNCHECKED_CAST")
            val map = LinkedHashMap(current as Map<String, Any?>)
            map["Fighting"] = true
            map["Normal"] = true
            m.ignoreImmunity = map
        }
    }

    /** Static, Poison Point: a 30% status on contact. */
    private fun HookRegistrar.contactStatus(id: String, status: String) = ability(id) {
        on("DamagingHit") {
            val src = sourceMon!!
            if (battle.checkMoveMakesContact(move, src, pokemon)) {
                if (randomChance(3, 10)) src.trySetStatus(status, pokemon)
            }
            Unit
        }
    }

    /** Air Lock, Cloud Nine. */
    private fun HookRegistrar.weatherLock(id: String, name: String) = ability(id) {
        on("SwitchIn") { state["switchingIn"] = true; Unit }
        on("Start") {
            if (Js.truthy(state["switchingIn"])) {
                add("-ability", pokemon, name)
                state["switchingIn"] = false
            }
            battle.eachEvent("WeatherChange", self)
            Unit
        }
        on("End") { battle.eachEvent("WeatherChange", self); Unit }
    }

    /** Berserk, Anger Shell: boosts when a hit takes the holder below half HP. */
    private fun HookRegistrar.halfHpTrigger(id: String, key: String, boosts: Map<String, Int>, smartTargetAware: Boolean) = ability(id) {
        on("Damage") {
            val e = sourceEffect
            val m = e as? ActiveMove
            val src = sourceMon
            state[key] = !(e?.effectType == "Move" && m != null && !Js.truthy(m.multihit) && !m.negateSecondary &&
                !(m.hasSheerForce && src?.hasAbility("sheerforce") == true))
            Unit
        }
        on("TryEatItem") {
            val item = relay as EffectLike
            val healingItems = listOf("aguavberry", "enigmaberry", "figyberry", "iapapaberry", "magoberry", "sitrusberry",
                "wikiberry", "oranberry", "berryjuice")
            if (item.id in healingItems) return@on if (state.has(key)) state[key] else Unit
            true
        }
        on("AfterMoveSecondary") {
            state[key] = true
            val t = pokemon
            val src = sourceMon
            val m = move
            if (src == null || src === t || t.hp == 0 || m.totalDamage == 0) return@on Unit
            val lastAttackedBy = t.getLastAttackedBy() ?: return@on Unit
            val multi = Js.truthy(m.multihit) && (!smartTargetAware || m.smartTarget != true)
            val damage = if (multi) m.totalDamage else lastAttackedBy.damage
            if (t.hp <= t.maxhp / 2.0 && t.hp + damage > t.maxhp / 2.0) battle.boost(boosts, t, t)
            Unit
        }
    }

    private fun schooling(p: Pokemon, checkHp: Boolean) {
        if (p.baseSpecies.baseSpecies != "Wishiwashi" || p.level < 20 || p.transformed || (checkHp && p.hp == 0)) return
        if (p.hp > p.maxhp / 4.0) {
            if (p.species.id == "wishiwashi") p.formeChange("Wishiwashi-School")
        } else {
            if (p.species.id == "wishiwashischool") p.formeChange("Wishiwashi")
        }
    }

    /** Power Construct and Tera Shift: the new forme's HP stat. */
    internal fun recalcMaxhp(p: Pokemon): Int {
        val base = p.species.baseStats.getValue("hp")
        val iv = p.set.ivs["hp"] ?: 31
        val ev = p.set.evs["hp"] ?: 0
        return Math.floor(Math.floor(2.0 * base + iv + Math.floor(ev / 4.0) + 100) * p.level / 100 + 10).toInt()
    }

    // endregion
}
