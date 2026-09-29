package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** Abilities from `data/abilities.js`, ported in usage order (first part). */
object PortAbilitiesA : HookSet() {
    @Suppress("UNCHECKED_CAST")
    internal fun boosts(value: Any?): MutableMap<String, Int> = value as MutableMap<String, Int>

    /** `this.effectState.target` for an ability: its holder. */
    internal val HookCall.holder: Pokemon get() = state.target as Pokemon

    /** JS `effect?.status`: a status the effect inflicts, if it is a status move or condition. */
    internal fun statusOf(effect: Any?): Any? = when (effect) {
        is ActiveMove -> effect.status
        is HitData -> effect.hitStatus
        is EffectLike -> effect.data("status")
        else -> null
    }

    /** JS `effect.secondaries` truthiness for a boost's source effect. */
    internal fun hasSecondaries(effect: Any?): Boolean = when (effect) {
        is ActiveMove -> effect.secondaries != null
        is HitData -> effect.hitSecondaries != null
        is EffectLike -> effect.data("secondaries") != null
        else -> false
    }

    /** A secondary effect whose chance a handler changed (`secondary.chance *= 2`). */
    private class ChanceHit(private val base: HitData, override val chance: Int?) : HitData by base

    override fun HookRegistrar.define() {
        ability("serenegrace") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                m.secondaries?.let { list ->
                    for (i in list.indices) {
                        val secondary = list[i]
                        val chance = secondary.chance
                        if (chance != null && chance != 0) list[i] = ChanceHit(secondary, chance * 2)
                    }
                }
                val self = m.self
                val selfChance = self?.chance
                if (self != null && selfChance != null && selfChance != 0) m.self = ChanceHit(self, selfChance * 2)
                Unit
            }
            on("SwitchOut") {
                if (pokemon.species.id == "meloettapirouette") pokemon.formeChange("Meloetta", self, true)
                Unit
            }
        }
        ability("sheerforce") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.secondaries != null) {
                    m.secondaries = null
                    m.self = null
                    if (m.id == "clangoroussoulblaze") m.selfBoost = null
                    m.hasSheerForce = true
                }
                Unit
            }
            on("BasePower") { if (move.hasSheerForce) chainModify(intArrayOf(5325, 4096)) else Unit }
        }
        ability("unaware") {
            on("AnyModifyBoost") {
                val b = boosts(relay)
                val unawareUser = state.target
                val p = target
                if (unawareUser === p) return@on Unit
                if (unawareUser === battle.activePokemon && p === battle.activeTarget) {
                    b["def"] = 0
                    b["spd"] = 0
                    b["evasion"] = 0
                }
                if (p === battle.activePokemon && unawareUser === battle.activeTarget) {
                    b["atk"] = 0
                    b["def"] = 0
                    b["spa"] = 0
                    b["accuracy"] = 0
                }
                Unit
            }
        }
        ability("speedboost") {
            on("Residual") {
                if (pokemon.activeTurns != 0) boost(mapOf("spe" to 1))
                Unit
            }
        }
        weatherSetter("drought", "sunnyday", "groudon")
        ability("primordialsea") {
            on("Start") { field.setWeather("primordialsea"); Unit }
            on("AnySetWeather") { strongWeatherGuard(this, "primordialsea") }
            on("End") { strongWeatherEnd(this, "primordialsea") }
        }
        redirect("lightningrod", "Electric", "spa", "Lightning Rod")
        redirect("stormdrain", "Water", "spa", "Storm Drain")
        ability("prismarmor") {
            on("SourceModifyDamage") {
                if ((source as Pokemon).getMoveHitData(move).typeMod > 0) chainModify(0.75) else Unit
            }
        }
        ability("infiltrator") {
            on("ModifyMove") { (relay as ActiveMove).infiltrates = true; Unit }
        }
        pinch("overgrow", "Grass")
        pinch("torrent", "Water")
        ability("illusion") {
            on("BeforeSwitchIn") {
                val p = pokemon
                p.illusion = null
                var i = p.side.pokemon.size - 1
                while (i > p.position) {
                    val possibleTarget = p.side.pokemon[i]
                    if (!possibleTarget.fainted) {
                        if (p.terastallized == null || possibleTarget.species.baseSpecies != "Ogerpon") p.illusion = possibleTarget
                        break
                    }
                    i--
                }
                Unit
            }
            on("DamagingHit") {
                val t = pokemon
                if (t.illusion != null) battle.singleEvent("End", dex.ability("Illusion"), t.abilityState, t, source, move)
                Unit
            }
            on("End") {
                val p = pokemon
                if (p.illusion != null) {
                    p.illusion = null
                    val details = p.species.name + (if (p.level == 100) "" else ", L" + p.level) +
                        (if (p.gender == "") "" else ", " + p.gender) + (if (p.set.shiny) ", shiny" else "")
                    add("replace", p, details)
                    add("-end", p, "Illusion")
                }
                Unit
            }
            on("Faint") { pokemon.illusion = null; Unit }
        }
        ability("turboblaze") {
            on("Start") { add("-ability", pokemon, "Turboblaze"); Unit }
            on("ModifyMove") { (relay as ActiveMove).ignoreAbility = true; Unit }
        }
        ability("sandstream") {
            on("Start") { field.setWeather("sandstorm"); Unit }
        }
        ability("sandrush") {
            on("ModifySpe") { if (field.isWeather("sandstorm")) chainModify(2) else Unit }
            on("Immunity") { if (relay == "sandstorm") false else Unit }
        }
        ability("windrider") {
            on("Start") {
                val p = pokemon
                if (p.side.sideConditions["tailwind"] != null) battle.boost(mapOf("atk" to 1), p, p)
                Unit
            }
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.flag("wind")) {
                    if (!Js.truthy(battle.boost(mapOf("atk" to 1), t, t))) add("-immune", t, "[from] ability: Wind Rider")
                    null
                } else Unit
            }
            on("AllySideConditionStart") {
                val p = holder
                if ((effect as EffectLike).id == "tailwind") battle.boost(mapOf("atk" to 1), p, p)
                Unit
            }
        }
        ability("sapsipper") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.type == "Grass") {
                    if (!Js.truthy(boost(mapOf("atk" to 1)))) add("-immune", t, "[from] ability: Sap Sipper")
                    null
                } else Unit
            }
            on("AllyTryHitSide") {
                val src = sourceMon
                if (src === state.target || !pokemon.isAlly(src)) return@on Unit
                if (move.type == "Grass") battle.boost(mapOf("atk" to 1), holder)
                Unit
            }
        }
        ability("tintedlens") {
            on("ModifyDamage") { if ((source as Pokemon).getMoveHitData(move).typeMod < 0) chainModify(2) else Unit }
        }
        ability("reckless") {
            on("BasePower") { if (move.recoil != null || move.hasCrashDamage) chainModify(intArrayOf(4915, 4096)) else Unit }
        }
        terrainSetter("grassysurge", "grassyterrain")
        terrainSetter("psychicsurge", "psychicterrain")
        weatherEvasion("snowcloak", "hail", listOf("hail", "snow"))
        weatherEvasion("sandveil", "sandstorm", listOf("sandstorm"))
        ability("poisonheal") {
            on("Damage") {
                val e = sourceEffect
                if (e?.id == "psn" || e?.id == "tox") {
                    heal(pokemon.baseMaxhp / 8.0)
                    false
                } else Unit
            }
        }
        ability("leafguard") {
            on("SetStatus") {
                val t = pokemon
                if (t.effectiveWeather() in listOf("sunnyday", "desolateland")) {
                    if (Js.truthy(statusOf(effect))) add("-immune", t, "[from] ability: Leaf Guard")
                    false
                } else Unit
            }
            on("TryAddVolatile") {
                val t = pokemon
                if ((relay as EffectLike).id == "yawn" && t.effectiveWeather() in listOf("sunnyday", "desolateland")) {
                    add("-immune", t, "[from] ability: Leaf Guard")
                    null
                } else Unit
            }
        }
        ability("guarddog") {
            on("DragOut") { add("-activate", pokemon, "ability: Guard Dog"); null }
            on("TryBoost") {
                val b = boosts(relay)
                val t = pokemon
                if (sourceEffect?.name == "Intimidate" && (b["atk"] ?: 0) != 0) {
                    b.remove("atk")
                    battle.boost(mapOf("atk" to 1), t, t, null, false, true)
                }
                Unit
            }
        }
        contactSlow("gooey", "Gooey")
        contactSlow("tanglinghair", "Tangling Hair")
        ability("asonespectrier") {
            on("PreStart") {
                add("-ability", pokemon, "As One")
                add("-ability", pokemon, "Unnerve")
                state["unnerved"] = true
                Unit
            }
            on("End") { state["unnerved"] = false; Unit }
            on("FoeTryEatItem") { !Js.truthy(state["unnerved"]) }
            on("SourceAfterFaint") {
                val e = sourceEffect
                if (e != null && e.effectType == "Move") {
                    val src = sourceMon
                    battle.boost(mapOf("spa" to relayInt), src, src, dex.ability("grimneigh"))
                }
                Unit
            }
        }
        ruin("beadsofruin", "Beads of Ruin", "SpD", "ruinedSpD", checkHolder = true)
        ruin("tabletsofruin", "Tablets of Ruin", "Atk", "ruinedAtk", checkHolder = false)
        ruin("vesselofruin", "Vessel of Ruin", "SpA", "ruinedSpA", checkHolder = false)
        ability("disguise") {
            on("Damage") {
                val t = pokemon
                if (sourceEffect?.effectType == "Move" && t.species.id in listOf("mimikyu", "mimikyutotem")) {
                    add("-activate", t, "ability: Disguise")
                    state["busted"] = true
                    0
                } else Unit
            }
            on("CriticalHit") {
                val t = targetMon ?: return@on Unit
                val m = move
                if (t.species.id !in listOf("mimikyu", "mimikyutotem")) return@on Unit
                val hitSub = t.volatiles["substitute"] != null && !m.flag("bypasssub") && !m.infiltrates
                if (hitSub) return@on Unit
                if (!t.runImmunity(m.type)) return@on Unit
                false
            }
            on("Effectiveness") {
                val t = targetMon ?: return@on Unit
                val m = move
                if (m.category == "Status") return@on Unit
                if (t.species.id !in listOf("mimikyu", "mimikyutotem")) return@on Unit
                val hitSub = t.volatiles["substitute"] != null && !m.flag("bypasssub") && !m.infiltrates
                if (hitSub) return@on Unit
                if (!t.runImmunity(m.type)) return@on Unit
                0
            }
            on("Update") {
                val p = pokemon
                if (p.species.id in listOf("mimikyu", "mimikyutotem") && Js.truthy(state["busted"])) {
                    val speciesid = if (p.species.id == "mimikyutotem") "Mimikyu-Busted-Totem" else "Mimikyu-Busted"
                    p.formeChange(speciesid, self, true)
                    battle.damage(p.baseMaxhp / 8.0, p, p, dex.species(speciesid))
                }
                Unit
            }
        }
        ability("fullmetalbody") {
            on("TryBoost") { TopAbilities.clearBodyTryBoost(this, "[from] ability: Full Metal Body") }
        }
        ability("gulpmissile") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (src.hp == 0 || !src.isActive || t.isSemiInvulnerable()) return@on Unit
                if (t.species.id in listOf("cramorantgulping", "cramorantgorging")) {
                    battle.damage(src.baseMaxhp / 4.0, src, t)
                    if (t.species.id == "cramorantgulping") battle.boost(mapOf("def" to -1), src, t, null, true)
                    else src.trySetStatus("par", t, move)
                    t.formeChange("cramorant", move)
                }
                Unit
            }
            on("SourceTryPrimaryHit") {
                val src = sourceMon!!
                val e = sourceEffect
                if (e?.id == "surf" && src.hasAbility("gulpmissile") && src.species.name == "Cramorant") {
                    val forme = if (src.hp <= src.maxhp / 2.0) "cramorantgorging" else "cramorantgulping"
                    src.formeChange(forme, e)
                }
                Unit
            }
        }
        ability("iceface") {
            on("Start") {
                val p = pokemon
                if (field.isWeather(listOf("hail", "snow")) && p.species.id == "eiscuenoice") {
                    add("-activate", p, "ability: Ice Face")
                    state["busted"] = false
                    p.formeChange("Eiscue", self, true)
                }
                Unit
            }
            on("Damage") {
                val t = pokemon
                val e = sourceEffect
                if (e?.effectType == "Move" && (e as? ActiveMove)?.category == "Physical" && t.species.id == "eiscue") {
                    add("-activate", t, "ability: Ice Face")
                    state["busted"] = true
                    0
                } else Unit
            }
            on("CriticalHit") {
                val t = targetMon ?: return@on Unit
                val m = move
                if (m.category != "Physical" || t.species.id != "eiscue") return@on Unit
                if (t.volatiles["substitute"] != null && !(m.flag("bypasssub") || m.infiltrates)) return@on Unit
                if (!t.runImmunity(m.type)) return@on Unit
                false
            }
            on("Effectiveness") {
                val t = targetMon ?: return@on Unit
                val m = move
                if (m.category != "Physical" || t.species.id != "eiscue") return@on Unit
                val hitSub = t.volatiles["substitute"] != null && !m.flag("bypasssub") && !m.infiltrates
                if (hitSub) return@on Unit
                if (!t.runImmunity(m.type)) return@on Unit
                0
            }
            on("Update") {
                val p = pokemon
                if (p.species.id == "eiscue" && Js.truthy(state["busted"])) p.formeChange("Eiscue-Noice", self, true)
                Unit
            }
            on("WeatherChange") {
                val p = pokemon
                if (Js.truthy(sourceEffect?.data("suppressWeather"))) return@on Unit
                if (p.hp == 0) return@on Unit
                if (field.isWeather(listOf("hail", "snow")) && p.species.id == "eiscuenoice") {
                    add("-activate", p, "ability: Ice Face")
                    state["busted"] = false
                    p.formeChange("Eiscue", self, true)
                }
                Unit
            }
        }
        ability("orichalcumpulse") {
            on("Start") {
                val p = pokemon
                if (Js.truthy(field.setWeather("sunnyday"))) add("-activate", p, "Orichalcum Pulse", "[source]")
                else if (field.isWeather("sunnyday")) add("-activate", p, "ability: Orichalcum Pulse")
                Unit
            }
            on("ModifyAtk") {
                if (pokemon.effectiveWeather() in listOf("sunnyday", "desolateland")) chainModify(intArrayOf(5461, 4096)) else Unit
            }
        }
        ability("slowstart") {
            on("Start") { pokemon.addVolatile("slowstart"); Unit }
            on("End") {
                pokemon.volatiles.remove("slowstart")
                add("-end", pokemon, "Slow Start", "[silent]")
                Unit
            }
            condition {
                on("Start") { add("-start", target, "ability: Slow Start"); Unit }
                on("ModifyAtk") { chainModify(0.5) }
                on("ModifySpe") { chainModify(0.5) }
                on("End") { add("-end", target, "Slow Start"); Unit }
            }
        }
        ability("truant") {
            on("Start") {
                val p = pokemon
                p.removeVolatile("truant")
                if (p.activeTurns != 0 && (p.moveThisTurnResult !== Unit || battle.queue.willMove(p) == null)) p.addVolatile("truant")
                Unit
            }
            on("BeforeMove") {
                val p = pokemon
                if (p.removeVolatile("truant")) {
                    add("cant", p, "ability: Truant")
                    return@on false
                }
                p.addVolatile("truant")
                Unit
            }
        }
        boostAbsorb("wellbakedbody", "Fire", mapOf("def" to 2), "Well-Baked Body")
        boostAbsorb("motordrive", "Electric", mapOf("spe" to 1), "Motor Drive")
        ability("triage") {
            on("ModifyPriority") { if (activeMove?.flag("heal") == true) Js.number(relayNum + 3) else Unit }
        }
        ate("pixilate", "Fairy")
        ate("refrigerate", "Ice")
        ate("galvanize", "Electric")
        protean("protean", "Protean")
        protean("libero", "Libero")
        ability("insomnia") {
            cureOnUpdate(this, "Insomnia", listOf("slp"))
            blockStatus(this, "Insomnia", listOf("slp"))
            on("TryAddVolatile") {
                if ((relay as EffectLike).id == "yawn") {
                    add("-immune", target, "[from] ability: Insomnia")
                    null
                } else Unit
            }
        }
        ability("vitalspirit") {
            cureOnUpdate(this, "Vital Spirit", listOf("slp"))
            blockStatus(this, "Vital Spirit", listOf("slp"))
            on("TryAddVolatile") {
                if ((relay as EffectLike).id == "yawn") {
                    add("-immune", target, "[from] ability: Vital Spirit")
                    null
                } else Unit
            }
        }
        ability("limber") {
            cureOnUpdate(this, "Limber", listOf("par"))
            blockStatus(this, "Limber", listOf("par"))
        }
        ability("soundproof") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.flag("sound")) {
                    add("-immune", t, "[from] ability: Soundproof")
                    null
                } else Unit
            }
            on("AllyTryHitSide") {
                if (move.flag("sound")) add("-immune", state.target, "[from] ability: Soundproof")
                Unit
            }
        }
        ability("weakarmor") {
            on("DamagingHit") {
                if (move.category == "Physical") battle.boost(linkedMapOf("def" to -1, "spe" to 2), pokemon, pokemon)
                Unit
            }
        }
        ability("roughskin") {
            on("DamagingHit") {
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, pokemon, true)) battle.damage(src.baseMaxhp / 8.0, src, pokemon)
                Unit
            }
        }
        ability("ironbarbs") {
            on("DamagingHit") {
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, pokemon, true)) battle.damage(src.baseMaxhp / 8.0, src, pokemon)
                Unit
            }
        }
        ability("solarpower") {
            on("ModifySpA") { if (pokemon.effectiveWeather() in listOf("sunnyday", "desolateland")) chainModify(1.5) else Unit }
            on("Weather") {
                val t = pokemon
                if (t.hasItem("utilityumbrella")) return@on Unit
                val id = sourceEffect?.id
                if (id == "sunnyday" || id == "desolateland") battle.damage(t.baseMaxhp / 8.0, t, t)
                Unit
            }
        }
        ability("icebody") {
            on("Weather") {
                val id = sourceEffect?.id
                if (id == "hail" || id == "snow") heal(pokemon.baseMaxhp / 16.0)
                Unit
            }
            on("Immunity") { if (relay == "hail") false else Unit }
        }
        ability("armortail") {
            on("FoeTryMove") {
                val m = move
                val targetAllExceptions = listOf("perishsong", "flowershield", "rototiller")
                if (m.target == "foeSide" || (m.target == "all" && m.id !in targetAllExceptions)) return@on Unit
                val armorTailHolder = holder
                if ((sourceMon?.isAlly(armorTailHolder) == true || m.target == "all") && m.priority > 0.1) {
                    battle.attrLastMove("[still]")
                    add("cant", armorTailHolder, "ability: Armor Tail", m, "[of] $target")
                    return@on false
                }
                Unit
            }
        }
        ability("poisontouch") {
            on("SourceDamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (t.hasAbility("shielddust") || t.hasItem("covertcloak")) return@on Unit
                if (battle.checkMoveMakesContact(move, t, src)) {
                    if (randomChance(3, 10)) t.trySetStatus("psn", src)
                }
                Unit
            }
        }
        ability("seedsower") {
            on("DamagingHit") { field.setTerrain("grassyterrain"); Unit }
        }
        ability("owntempo") {
            on("Update") {
                val p = pokemon
                if (p.volatiles["confusion"] != null) {
                    add("-activate", p, "ability: Own Tempo")
                    p.removeVolatile("confusion")
                }
                Unit
            }
            on("TryAddVolatile") { if ((relay as EffectLike).id == "confusion") null else Unit }
            on("Hit") {
                if (activeMove?.volatileStatus == "confusion") add("-immune", target, "confusion", "[from] ability: Own Tempo")
                Unit
            }
            on("TryBoost") {
                val b = boosts(relay)
                if (sourceEffect?.name == "Intimidate" && (b["atk"] ?: 0) != 0) {
                    b.remove("atk")
                    add("-fail", target, "unboost", "Attack", "[from] ability: Own Tempo", "[of] $target")
                }
                Unit
            }
        }
        ability("commander") {
            on("Update") {
                val p = pokemon
                if (battle.gameType != "doubles") return@on Unit
                val ally = p.allies().firstOrNull()
                if (ally == null || p.baseSpecies.baseSpecies != "Tatsugiri" || ally.baseSpecies.baseSpecies != "Dondozo") {
                    if (p.getVolatile("commanding") != null) p.removeVolatile("commanding")
                    return@on Unit
                }
                if (p.getVolatile("commanding") == null) {
                    if (ally.getVolatile("commanded") != null) return@on Unit
                    battle.queue.cancelAction(p)
                    add("-activate", p, "ability: Commander", "[of] $ally")
                    p.addVolatile("commanding")
                    ally.addVolatile("commanded", p)
                } else {
                    if (!ally.fainted) return@on Unit
                    p.removeVolatile("commanding")
                }
                Unit
            }
        }
        flagPower("strongjaw", "bite", 1.5)
        flagPower("sharpness", "slicing", 1.5)
        flagPower("megalauncher", "pulse", 1.5)
        flagPowerFraction("ironfist", "punch", intArrayOf(4915, 4096))
        ability("mirrorarmor") {
            on("TryBoost") {
                val t = pokemon
                val src = sourceMon
                if (src == null || t === src || relay == null || sourceEffect?.name == "Mirror Armor") return@on Unit
                val b = boosts(relay)
                for (key in b.keys.toList()) {
                    val amount = b[key] ?: continue
                    if (amount < 0) {
                        if (t.boosts[key] == -6) continue
                        val negativeBoost = linkedMapOf(key to amount)
                        b.remove(key)
                        if (src.hp != 0) {
                            add("-ability", t, "Mirror Armor")
                            battle.boost(negativeBoost, src, t, null, true)
                        }
                    }
                }
                Unit
            }
        }
        typeAttack("rockypayload", "Rock")
        typeAttack("steelworker", "Steel")
        typeAttack("dragonsmaw", "Dragon")
        ability("healer") {
            on("Residual") {
                val p = pokemon
                for (allyActive in p.adjacentAllies()) {
                    if (allyActive.status.isNotEmpty() && randomChance(3, 10)) {
                        add("-activate", p, "ability: Healer")
                        allyActive.cureStatus()
                    }
                }
                Unit
            }
        }
        ability("slushrush") {
            on("ModifySpe") { if (field.isWeather(listOf("hail", "snow"))) chainModify(2) else Unit }
        }
        ability("noguard") {
            on("AnyInvulnerability") {
                if (activeMove != null && (source === state.target || target === state.target)) 0 else Unit
            }
            on("AnyAccuracy") {
                if (activeMove != null && (source === state.target || target === state.target)) true else relay
            }
        }
        ability("heavymetal") {
            on("ModifyWeight") { Js.number(relayNum * 2) }
        }
        ability("lightmetal") {
            on("ModifyWeight") { Js.trunc(relayNum / 2) }
        }
        ability("steelyspirit") {
            on("AllyBasePower") { if (move.type == "Steel") chainModify(1.5) else Unit }
        }
        tracksTarget("stalwart")
        tracksTarget("propellertail")
        ability("symbiosis") {
            on("AllyAfterUseItem") {
                val p = pokemon
                if (Js.truthy(p.switchFlag)) return@on Unit
                val src = holder
                val myItem = src.takeItem() as? EffectLike ?: return@on Unit
                if (!Js.truthy(battle.singleEvent("TakeItem", myItem, src.itemState, p, src, self, myItem)) || !p.setItem(myItem.id)) {
                    src.item = myItem.id
                    return@on Unit
                }
                add("-activate", src, "ability: Symbiosis", myItem, "[of] $p")
                Unit
            }
        }
        ability("costar") {
            on("Start") {
                val p = pokemon
                val ally = p.allies().firstOrNull() ?: return@on Unit
                for ((i, value) in ally.boosts) p.boosts[i] = value
                for (volatile in listOf("focusenergy", "gmaxchistrike", "laserfocus")) {
                    if (ally.volatiles[volatile] != null) {
                        p.addVolatile(volatile)
                        if (volatile == "gmaxchistrike") p.volatiles[volatile]!!["layers"] = ally.volatiles[volatile]!!["layers"]
                    } else {
                        p.removeVolatile(volatile)
                    }
                }
                add("-copyboost", p, ally, "[from] ability: Costar")
                Unit
            }
        }
        statGuard("hypercutter", "atk", "Attack", "Hyper Cutter", octolock = false)
        statGuard("bigpecks", "def", "Defense", "Big Pecks", octolock = true)
        statGuard("mindseye", "accuracy", "accuracy", "Mind's Eye", octolock = false)
        statGuard("illuminate", "accuracy", "accuracy", "Illuminate", octolock = false)
        ability("furcoat") {
            on("ModifyDef") { chainModify(2) }
        }
        ability("fluffy") {
            on("SourceModifyDamage") {
                var mod = 1.0
                if (move.type == "Fire") mod *= 2
                if (move.flag("contact")) mod /= 2
                chainModify(mod)
            }
        }
        ability("stickyhold") {
            on("TakeItem") {
                val p = pokemon
                val active = battle.activeMove ?: error("Battle.activeMove is null")
                if (p.hp == 0 || p.item == "stickybarb") return@on Unit
                val src = sourceMon
                if ((src != null && src !== p) || active.id == "knockoff") {
                    add("-activate", p, "ability: Sticky Hold")
                    return@on false
                }
                Unit
            }
        }
        ability("merciless") {
            on("ModifyCritRatio") {
                val t = sourceMon
                if (t != null && t.status in listOf("psn", "tox")) 5 else Unit
            }
        }
        ability("curiousmedicine") {
            on("Start") {
                val p = pokemon
                for (ally in p.adjacentAllies()) {
                    ally.clearBoosts()
                    add("-clearboost", ally, "[from] ability: Curious Medicine", "[of] $p")
                }
                Unit
            }
        }
        ability("cheekpouch") {
            on("EatItem") { heal(pokemon.baseMaxhp / 3.0); Unit }
        }
        ability("toxicboost") {
            on("BasePower") {
                val a = pokemon
                if ((a.status == "psn" || a.status == "tox") && move.category == "Physical") chainModify(1.5) else Unit
            }
        }
        ability("flareboost") {
            on("BasePower") { if (pokemon.status == "brn" && move.category == "Special") chainModify(1.5) else Unit }
        }
        ability("klutz") {
            on("Start") {
                val p = pokemon
                battle.singleEvent("End", p.getItem(), p.itemState, p)
                Unit
            }
        }
        ability("aurabreak") {
            on("Start") {
                if (battle.suppressingAbility(pokemon)) return@on Unit
                add("-ability", pokemon, "Aura Break")
                Unit
            }
            on("AnyTryPrimaryHit") {
                val m = effect as? ActiveMove ?: return@on Unit
                if (target === source || m.category == "Status") return@on Unit
                m.hasAuraBreak = true
                Unit
            }
        }
        ability("colorchange") {
            on("AfterMoveSecondary") {
                val t = pokemon
                if (t.hp == 0) return@on Unit
                val m = move
                val type = m.type
                if (t.isActive && m.effectType == "Move" && m.category != "Status" && type != "???" && !t.hasType(type)) {
                    if (!t.setType(type)) return@on false
                    add("-start", t, "typechange", type, "[from] ability: Color Change")
                    if (t.side.active.size == 2 && t.position == 1) {
                        val action = battle.queue.willMove(t)
                        if (action != null && action.move?.id == "curse") action.targetLoc = -1
                    }
                }
                Unit
            }
        }
        aura("darkaura", "Dark Aura", "Dark")
        aura("fairyaura", "Fairy Aura", "Fairy") {
            on("PreStart") {
                if (pokemon.species.id == "xerneas") pokemon.formeChange("xerneasactive", null)
                Unit
            }
        }
        ability("desolateland") {
            on("Start") { field.setWeather("desolateland"); Unit }
            on("AnySetWeather") { strongWeatherGuard(this, "desolateland") }
            on("End") { strongWeatherEnd(this, "desolateland") }
        }
        embody("embodyaspectteal", "Ogerpon-Teal-Tera", "spe")
        embody("embodyaspectcornerstone", "Ogerpon-Cornerstone-Tera", "def")
        embody("embodyaspectwellspring", "Ogerpon-Wellspring-Tera", "spd")
        ability("forewarn") {
            on("Start") {
                val p = pokemon
                var warnMoves = ArrayList<Pair<EffectLike, Pokemon>>()
                var warnBp = 1
                for (t in p.foes()) {
                    for (moveSlot in t.moveSlots) {
                        val m = dex.moveOrPlaceholder(moveSlot.move)
                        var bp = m.basePower
                        if (Js.truthy(m.data("ohko"))) bp = 150
                        if (m.id == "counter" || m.id == "metalburst" || m.id == "mirrorcoat") bp = 120
                        if (bp == 1) bp = 80
                        if (bp == 0 && m.category != "Status") bp = 80
                        if (bp > warnBp) {
                            warnMoves = arrayListOf(m to t)
                            warnBp = bp
                        } else if (bp == warnBp) {
                            warnMoves.add(m to t)
                        }
                    }
                }
                if (warnMoves.isEmpty()) return@on Unit
                val (warnMoveName, warnTarget) = battle.sample(warnMoves)
                add("-activate", p, "ability: Forewarn", warnMoveName, "[of] $warnTarget")
                Unit
            }
        }
        ability("innardsout") {
            on("DamagingHit") {
                val t = pokemon
                if (t.hp == 0) battle.damage(t.getUndynamaxedHP(relayInt), sourceMon, t)
                Unit
            }
        }
        ability("liquidooze") {
            on("SourceTryHeal") {
                if (sourceEffect?.id in listOf("drain", "leechseed", "strengthsap")) {
                    battle.damage(relayNum)
                    0
                } else Unit
            }
        }
        ability("neuroforce") {
            on("ModifyDamage") {
                val m = activeMove
                if (m != null && (source as Pokemon).getMoveHitData(m).typeMod > 0) chainModify(intArrayOf(5120, 4096)) else Unit
            }
        }
        ability("pastelveil") {
            on("Start") {
                val p = pokemon
                for (ally in p.alliesAndSelf()) {
                    if (ally.status in listOf("psn", "tox")) {
                        add("-activate", p, "ability: Pastel Veil")
                        ally.cureStatus()
                    }
                }
                Unit
            }
            on("Update") {
                val p = pokemon
                if (p.status in listOf("psn", "tox")) {
                    add("-activate", p, "ability: Pastel Veil")
                    p.cureStatus()
                }
                Unit
            }
            on("AllySwitchIn") {
                val p = pokemon
                if (p.status in listOf("psn", "tox")) {
                    add("-activate", state.target, "ability: Pastel Veil")
                    p.cureStatus()
                }
                Unit
            }
            blockStatus(this, "Pastel Veil", listOf("psn", "tox"))
            on("AllySetStatus") {
                if ((relay as EffectLike).id !in listOf("psn", "tox")) return@on Unit
                if (Js.truthy(statusOf(effect))) add("-block", target, "ability: Pastel Veil", "[of] ${state.target}")
                false
            }
        }
        ability("powerconstruct") {
            on("Residual") {
                val p = pokemon
                if (p.baseSpecies.baseSpecies != "Zygarde" || p.transformed || p.hp == 0) return@on Unit
                if (p.species.id == "zygardecomplete" || p.hp > p.maxhp / 2.0) return@on Unit
                add("-activate", p, "ability: Power Construct")
                p.formeChange("Zygarde-Complete", self, true)
                val base = p.species.baseStats.getValue("hp")
                val iv = p.set.ivs["hp"] ?: 31
                val ev = p.set.evs["hp"] ?: 0
                p.baseMaxhp = Math.floor(Math.floor((2 * base + iv + Math.floor(ev / 4.0) + 100)) * p.level / 100 + 10).toInt()
                val newMaxHP = if (p.volatiles["dynamax"] != null) 2 * p.baseMaxhp else p.baseMaxhp
                p.hp = newMaxHP - (p.maxhp - p.hp)
                p.maxhp = newMaxHP
                add("-heal", p, p.getHealth, "[silent]")
                Unit
            }
        }
        ability("simple") {
            on("ChangeBoost") {
                if (sourceEffect?.id == "zpower") return@on Unit
                val b = boosts(relay)
                for (key in b.keys.toList()) b[key] = b.getValue(key) * 2
                Unit
            }
        }
        ability("contrary") {
            on("ChangeBoost") {
                if (sourceEffect?.id == "zpower") return@on Unit
                val b = boosts(relay)
                for (key in b.keys.toList()) b[key] = b.getValue(key) * -1
                Unit
            }
        }
        ability("terashell") {
            on("Effectiveness") {
                val t = targetMon ?: return@on Unit
                if (t.species.name != "Terapagos-Terastal") return@on Unit
                if (Js.truthy(state["resisted"])) return@on -1
                val m = move
                if (m.category == "Status") return@on Unit
                if (!t.runImmunity(m.type)) return@on Unit
                if (t.hp < t.maxhp) return@on Unit
                add("-activate", t, "ability: Tera Shell")
                state["resisted"] = true
                -1
            }
            on("AnyAfterMove") { state["resisted"] = false; Unit }
        }
        ability("wimpout") {
            on("EmergencyExit") { emergencyExit(this, "ability: Wimp Out") }
        }
        ability("zenmode") {
            on("Residual") {
                val p = pokemon
                if (p.baseSpecies.baseSpecies != "Darmanitan" || p.transformed) return@on Unit
                val isGalarian = p.baseSpecies.forme == "Galar"
                if (p.hp <= p.maxhp / 2.0 && p.species.forme !in listOf("Zen", "Galar-Zen")) {
                    p.formeChange(if (isGalarian) "Darmanitan-Galar-Zen" else "Darmanitan-Zen")
                    p.addVolatile("zenmode")
                } else if (p.hp > p.maxhp / 2.0 && p.species.forme in listOf("Zen", "Galar-Zen")) {
                    p.addVolatile("zenmode")
                    p.formeChange(if (isGalarian) "Darmanitan-Galar" else "Darmanitan")
                    p.removeVolatile("zenmode")
                }
                Unit
            }
            on("End") {
                val p = pokemon
                if (p.volatiles["zenmode"] == null || p.hp == 0) return@on Unit
                p.transformed = false
                p.volatiles.remove("zenmode")
                val battleOnly = p.species.battleOnly
                if (p.species.baseSpecies == "Darmanitan" && Js.truthy(battleOnly)) {
                    p.formeChange(battleOnly as String, self, true, "[silent]")
                }
                Unit
            }
            condition {
                on("Start") {
                    val p = pokemon
                    if (!p.species.name.contains("Galar")) {
                        if (p.species.id != "darmanitanzen") p.formeChange("Darmanitan-Zen")
                    } else {
                        if (p.species.id != "darmanitangalarzen") p.formeChange("Darmanitan-Galar-Zen")
                    }
                    Unit
                }
                on("End") {
                    val p = pokemon
                    if (p.species.forme in listOf("Zen", "Galar-Zen")) p.formeChange(p.species.battleOnly as String)
                    Unit
                }
            }
        }
    }

    // region Shared shapes

    /** Drought and the primal weathers: skip while the holder is about to Primal Reverse. */
    private fun HookRegistrar.weatherSetter(id: String, weather: String, primalSpecies: String?) = ability(id) {
        on("Start") {
            val src = pokemon
            if (primalSpecies != null) {
                for (action in battle.queue.list) {
                    if (action.choice == "runPrimal" && action.pokemon === src && src.species.id == primalSpecies) return@on Unit
                    if (action.choice != "runSwitch" && action.choice != "runPrimal") break
                }
            }
            field.setWeather(weather)
            Unit
        }
    }

    private fun HookRegistrar.terrainSetter(id: String, terrain: String) = ability(id) {
        on("Start") { field.setTerrain(terrain); Unit }
    }

    /** Lightning Rod and Storm Drain: absorb the type for a boost, and pull single-target moves of it. */
    private fun HookRegistrar.redirect(id: String, type: String, stat: String, name: String) = ability(id) {
        on("TryHit") {
            val t = pokemon
            if (t !== source && move.type == type) {
                if (!Js.truthy(boost(mapOf(stat to 1)))) add("-immune", t, "[from] ability: $name")
                null
            } else Unit
        }
        on("AnyRedirectTarget") {
            val m = move
            if (m.type != type || m.flag("pledgecombo")) return@on Unit
            val redirectTarget = if (m.target in listOf("randomNormal", "adjacentFoe")) "normal" else m.target
            val holderMon = state.target as Pokemon
            if (battle.validTarget(holderMon, pokemon, redirectTarget)) {
                if (m.smartTarget == true) m.smartTarget = false
                if (holderMon !== relay) add("-activate", holderMon, "ability: $name")
                return@on holderMon
            }
            Unit
        }
    }

    /** Overgrow, Torrent, Blaze, Swarm. */
    private fun HookRegistrar.pinch(id: String, type: String) = ability(id) {
        on("ModifyAtk") { if (move.type == type && pokemon.hp <= pokemon.maxhp / 3.0) chainModify(1.5) else Unit }
        on("ModifySpA") { if (move.type == type && pokemon.hp <= pokemon.maxhp / 3.0) chainModify(1.5) else Unit }
    }

    /** Steelworker, Rocky Payload, Dragon's Maw, Transistor-style Atk and SpA boosts for one type. */
    private fun HookRegistrar.typeAttack(id: String, type: String) = ability(id) {
        on("ModifyAtk") { if (move.type == type) chainModify(1.5) else Unit }
        on("ModifySpA") { if (move.type == type) chainModify(1.5) else Unit }
    }

    private fun HookRegistrar.weatherEvasion(id: String, immunity: String, weathers: List<String>) = ability(id) {
        on("Immunity") { if (relay == immunity) false else Unit }
        on("ModifyAccuracy") {
            if (!Js.isNumber(relay)) return@on Unit
            if (field.isWeather(weathers)) chainModify(intArrayOf(3277, 4096)) else Unit
        }
    }

    /** Gooey and Tangling Hair. */
    private fun HookRegistrar.contactSlow(id: String, name: String) = ability(id) {
        on("DamagingHit") {
            val t = pokemon
            val src = sourceMon!!
            if (battle.checkMoveMakesContact(move, src, t, true)) {
                add("-ability", t, name)
                battle.boost(mapOf("spe" to -1), src, t, null, true)
            }
            Unit
        }
    }

    /** The four Treasures of Ruin. Beads checks the recorded holder still has the ability; the others do not. */
    private fun HookRegistrar.ruin(id: String, name: String, stat: String, key: String, checkHolder: Boolean) = ability(id) {
        on("Start") {
            if (battle.suppressingAbility(pokemon)) return@on Unit
            add("-ability", pokemon, name)
            Unit
        }
        on("AnyModify$stat") {
            val abilityHolder = state.target
            val statHolder = pokemon
            val m = activeMove ?: return@on Unit
            if (statHolder.hasAbility(name)) return@on Unit
            val recorded = m.extra[key] as? Pokemon
            if (checkHolder) {
                if (recorded?.hasAbility(name) != true) m.extra[key] = abilityHolder
            } else if (recorded == null) {
                m.extra[key] = abilityHolder
            }
            if (m.extra[key] !== abilityHolder) return@on Unit
            chainModify(0.75)
        }
    }

    /** Well-Baked Body, Motor Drive: `this.boost(...)` with the event's own target and source. */
    private fun HookRegistrar.boostAbsorb(id: String, type: String, boost: Map<String, Int>, name: String) = ability(id) {
        on("TryHit") {
            val t = pokemon
            if (t !== source && move.type == type) {
                if (!Js.truthy(boost(boost))) add("-immune", t, "[from] ability: $name")
                null
            } else Unit
        }
    }

    /** Pixilate, Refrigerate, Aerilate, Galvanize. */
    private fun HookRegistrar.ate(id: String, type: String) = ability(id) {
        on("ModifyType") {
            val m = relay as ActiveMove
            val noModifyType = listOf("judgment", "multiattack", "naturalgift", "revelationdance", "technoblast", "terrainpulse", "weatherball")
            if (m.type == "Normal" && m.id !in noModifyType && !(Js.truthy(m.isZ) && m.category != "Status") &&
                !(m.name == "Tera Blast" && pokemon.terastallized != null)) {
                m.type = type
                m.typeChangerBoosted = self
            }
            Unit
        }
        on("BasePower") { if (move.typeChangerBoosted === self) chainModify(intArrayOf(4915, 4096)) else Unit }
    }

    private fun HookRegistrar.protean(id: String, name: String) = ability(id) {
        on("PrepareHit") {
            val src = pokemon
            val m = move
            if (Js.truthy(state[id])) return@on Unit
            if (m.hasBounced || m.flag("futuremove") || m.sourceEffect == "snatch") return@on Unit
            val type = m.type
            if (type.isNotEmpty() && type != "???" && src.getTypes().joinToString(",") != type) {
                if (!src.setType(type)) return@on Unit
                state[id] = true
                add("-start", src, "typechange", type, "[from] ability: $name")
            }
            Unit
        }
        on("SwitchIn") { state.remove(id); Unit }
    }

    internal fun cureOnUpdate(hooks: EffectHooks, name: String, statuses: List<String>) = hooks.on("Update") {
        val p = pokemon
        if (p.status in statuses) {
            add("-activate", p, "ability: $name")
            p.cureStatus()
        }
        Unit
    }

    internal fun blockStatus(hooks: EffectHooks, name: String, statuses: List<String>) = hooks.on("SetStatus") {
        if ((relay as EffectLike).id !in statuses) return@on Unit
        if (Js.truthy(statusOf(effect))) add("-immune", target, "[from] ability: $name")
        false
    }

    private fun HookRegistrar.flagPower(id: String, flag: String, modifier: Double) = ability(id) {
        on("BasePower") { if (move.flag(flag)) chainModify(modifier) else Unit }
    }

    private fun HookRegistrar.flagPowerFraction(id: String, flag: String, modifier: IntArray) = ability(id) {
        on("BasePower") { if (move.flag(flag)) chainModify(modifier) else Unit }
    }

    private fun HookRegistrar.tracksTarget(id: String) = ability(id) {
        on("ModifyMove") {
            val m = relay as ActiveMove
            m.tracksTarget = m.target != "scripted"
            Unit
        }
    }

    /** Hyper Cutter, Big Pecks: block drops of one stat from others. */
    private fun HookRegistrar.statGuard(id: String, stat: String, statName: String, name: String, octolock: Boolean) = ability(id) {
        on("TryBoost") {
            if (source != null && target === source) return@on Unit
            val b = boosts(relay)
            val amount = b[stat] ?: 0
            if (amount != 0 && amount < 0) {
                b.remove(stat)
                if (!hasSecondaries(effect) && !(octolock && sourceEffect?.id == "octolock")) {
                    add("-fail", target, "unboost", statName, "[from] ability: $name", "[of] $target")
                }
            }
            Unit
        }
    }

    /** Dark Aura, Fairy Aura. */
    private fun HookRegistrar.aura(id: String, name: String, type: String, extra: EffectHooks.() -> Unit = {}) = ability(id) {
        extra()
        on("Start") {
            if (battle.suppressingAbility(pokemon)) return@on Unit
            add("-ability", pokemon, name)
            Unit
        }
        on("AnyBasePower") {
            val m = move
            if (target === source || m.category == "Status" || m.type != type) return@on Unit
            if (m.auraBooster?.hasAbility(name) != true) m.auraBooster = state.target as Pokemon
            if (m.auraBooster !== state.target) return@on Unit
            chainModify(intArrayOf(if (m.hasAuraBreak == true) 3072 else 5448, 4096))
        }
    }

    /** Desolate Land, Primordial Sea, Delta Stream: `onAnySetWeather`. */
    internal fun strongWeatherGuard(call: HookCall, weather: String): Any? = with(call) {
        val strongWeathers = listOf("desolateland", "primordialsea", "deltastream")
        if (field.getWeather().id == weather && (effect as EffectLike).id !in strongWeathers) false else Unit
    }

    /** Desolate Land, Primordial Sea, Delta Stream: `onEnd`. */
    internal fun strongWeatherEnd(call: HookCall, ability: String): Any? = with(call) {
        val p = pokemon
        if (field.weatherState.source !== p) return@with Unit
        for (t in battle.getAllActive()) {
            if (t === p) continue
            if (t.hasAbility(ability)) {
                field.weatherState.source = t
                return@with Unit
            }
        }
        field.clearWeather()
        Unit
    }

    /** Embody Aspect: a one-time boost when the Tera forme of Ogerpon comes in. */
    private fun HookRegistrar.embody(id: String, species: String, stat: String) = ability(id) {
        on("Start") {
            val p = pokemon
            if (p.baseSpecies.name == species && !Js.truthy(state["embodied"])) {
                state["embodied"] = true
                battle.boost(mapOf(stat to 1), p)
            }
            Unit
        }
        on("SwitchIn") { state.remove("embodied"); Unit }
    }

    /** Wimp Out and Emergency Exit. */
    internal fun emergencyExit(call: HookCall, activate: String): Any? = with(call) {
        val t = pokemon
        if (battle.canSwitch(t.side) == 0 || t.forceSwitchFlag || Js.truthy(t.switchFlag)) return@with Unit
        for (side in battle.sides) {
            for (active in side.active) active?.switchFlag = false
        }
        t.switchFlag = true
        add("-activate", t, activate)
        Unit
    }

    // endregion
}
