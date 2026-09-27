package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookCall
import jbro.cobblemon.mcc.betterai.engine.hooks.HookFn
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.HitData
import jbro.cobblemon.mcc.betterai.engine.sim.LiteralHit
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove

/** Abilities with code in `data/abilities.js`, the second porting batch. */
object PortAbilitiesB : HookSet() {
    @Suppress("UNCHECKED_CAST")
    private fun boosts(value: Any?): MutableMap<String, Int> = value as MutableMap<String, Int>

    /** A plain JS object passed where Showdown expects an effect, like Synchronize's `{status, id}`. */
    class PlainEffect(override val id: String, private val fields: Map<String, Any?>) : EffectLike {
        override val name: String get() = ""
        override val fullname: String get() = ""
        override val effectType: String get() = ""
        override val num: Int get() = 0
        override val hookKey: String get() = ""
        override fun handler(callbackName: String): Any? = null
        override fun declares(callbackName: String): Boolean = false
        override fun data(field: String): Any? = fields[field]
        override fun toString(): String = "[object Object]"
    }

    /** `effect?.status` for an effect handed to `SetStatus` handlers. */
    fun effectStatus(effect: Any?): Any? = when (effect) {
        null -> null
        is ActiveMove -> effect.status
        is EffectLike -> effect.data("status")
        else -> null
    }

    private fun HookCall.holder(): Pokemon = state.target as Pokemon

    override fun HookRegistrar.define() {
        breaker("moldbreaker", "Mold Breaker")
        breaker("teravolt", "Teravolt")
        ability("flamebody") {
            on("DamagingHit") {
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, pokemon)) {
                    if (randomChance(3, 10)) src.trySetStatus("brn", pokemon)
                }
                Unit
            }
        }
        ability("guts") {
            on("ModifyAtk") { if (pokemon.status.isNotEmpty()) chainModify(1.5) else Unit }
        }
        weatherSetter("drizzle", "raindance", "kyogre")
        ability("snowwarning") {
            on("Start") { field.setWeather("snow"); Unit }
        }
        ability("overcoat") {
            on("Immunity") { if (relay == "sandstorm" || relay == "hail" || relay == "powder") false else Unit }
            on("TryHit") {
                val t = pokemon
                if (move.flag("powder") && t !== source && dex.notImmune("powder", t.getTypes())) {
                    add("-immune", t, "[from] ability: Overcoat")
                    null
                } else Unit
            }
        }
        ability("compoundeyes") {
            on("SourceModifyAccuracy") { if (!Js.isNumber(relay)) Unit else chainModify(intArrayOf(5325, 4096)) }
        }
        pinch("blaze", "Fire")
        pinch("swarm", "Bug")
        ability("synchronize") {
            on("AfterSetStatus") {
                val status = relay as EffectLike
                val t = pokemon
                val src = sourceMon
                if (src == null || src === t) return@on Unit
                if (sourceEffect?.id == "toxicspikes") return@on Unit
                if (status.id == "slp" || status.id == "frz") return@on Unit
                add("-activate", t, "ability: Synchronize")
                src.trySetStatus(status.id, t, PlainEffect("synchronize", mapOf("status" to status.id, "id" to "synchronize")))
                Unit
            }
        }
        ability("dauntlessshield") {
            on("Start") {
                val p = pokemon
                if (p.shieldBoost) return@on Unit
                p.shieldBoost = true
                boost(mapOf("def" to 1), p)
                Unit
            }
        }
        ability("magicbounce") {
            on("TryHit") {
                val t = pokemon
                val m = move
                if (t === source || m.hasBounced || !m.flag("reflectable")) return@on Unit
                val newMove = dex.activeMove(m.id)
                newMove.hasBounced = true
                newMove.pranksterBoosted = false
                battle.actions.useMove(newMove, t, sourceMon)
                null
            }
            on("AllyTryHitSide") {
                val m = move
                if (pokemon.isAlly(sourceMon) || m.hasBounced || !m.flag("reflectable")) return@on Unit
                val newMove = dex.activeMove(m.id)
                newMove.hasBounced = true
                newMove.pranksterBoosted = false
                battle.actions.useMove(newMove, holder(), sourceMon)
                null
            }
        }
        ability("moody") {
            on("Residual") {
                val p = pokemon
                var stats = ArrayList<String>()
                val boost = LinkedHashMap<String, Int>()
                for ((statPlus, value) in p.boosts) {
                    if (statPlus == "accuracy" || statPlus == "evasion") continue
                    if (value < 6) stats.add(statPlus)
                }
                var randomStat: String? = if (stats.isNotEmpty()) battle.sample(stats) else null
                if (randomStat != null) boost[randomStat] = 2
                stats = ArrayList()
                for ((statMinus, value) in p.boosts) {
                    if (statMinus == "accuracy" || statMinus == "evasion") continue
                    if (value > -6 && statMinus != randomStat) stats.add(statMinus)
                }
                randomStat = if (stats.isNotEmpty()) battle.sample(stats) else null
                if (randomStat != null) boost[randomStat] = -1
                boost(boost, p, p)
                Unit
            }
        }
        faintBoost("moxie", "atk")
        faintBoost("chillingneigh", "atk")
        faintBoost("grimneigh", "spa")
        ability("oblivious") {
            on("Update") {
                val p = pokemon
                if (p.volatiles["attract"] != null) {
                    add("-activate", p, "ability: Oblivious")
                    p.removeVolatile("attract")
                    add("-end", p, "move: Attract", "[from] ability: Oblivious")
                }
                if (p.volatiles["taunt"] != null) {
                    add("-activate", p, "ability: Oblivious")
                    p.removeVolatile("taunt")
                }
                Unit
            }
            on("Immunity") { if (relay == "attract") false else Unit }
            on("TryHit") {
                if (move.id == "attract" || move.id == "captivate" || move.id == "taunt") {
                    add("-immune", pokemon, "[from] ability: Oblivious")
                    null
                } else Unit
            }
            on("TryBoost") {
                val b = boosts(relay)
                if (sourceEffect?.name == "Intimidate" && (b["atk"] ?: 0) != 0) {
                    b.remove("atk")
                    add("-fail", target, "unboost", "Attack", "[from] ability: Oblivious", "[of] $target")
                }
                Unit
            }
        }
        fullHpHalver("multiscale")
        fullHpHalver("shadowshield")
        ability("harvest") {
            on("Residual") {
                val p = pokemon
                if (field.isWeather(listOf("sunnyday", "desolateland")) || randomChance(1, 2)) {
                    if (p.hp != 0 && p.item.isEmpty() && dex.item(p.lastItem).bool("isBerry")) {
                        p.setItem(p.lastItem)
                        p.lastItem = ""
                        add("-item", p, p.getItem(), "[from] ability: Harvest")
                    }
                }
                Unit
            }
        }
        ability("shadowtag") {
            on("FoeTrapPokemon") {
                val p = pokemon
                if (!p.hasAbility("shadowtag") && p.isAdjacent(holder())) p.tryTrap(true)
                Unit
            }
            on("FoeMaybeTrapPokemon") {
                val p = pokemon
                val src = sourceMon ?: state.target as? Pokemon
                if (src == null || !p.isAdjacent(src)) return@on Unit
                if (!p.hasAbility("shadowtag")) p.maybeTrapped = true
                Unit
            }
        }
        ability("arenatrap") {
            on("FoeTrapPokemon") {
                val p = pokemon
                if (!p.isAdjacent(holder())) return@on Unit
                if (p.isGrounded() == true) p.tryTrap(true)
                Unit
            }
            on("FoeMaybeTrapPokemon") {
                val p = pokemon
                val src = sourceMon ?: state.target as? Pokemon
                if (src == null || !p.isAdjacent(src)) return@on Unit
                if (p.isGrounded(!p.knownType) == true) p.maybeTrapped = true
                Unit
            }
        }
        ability("magnetpull") {
            on("FoeTrapPokemon") {
                val p = pokemon
                if (p.hasType("Steel") && p.isAdjacent(holder())) p.tryTrap(true)
                Unit
            }
            on("FoeMaybeTrapPokemon") {
                val p = pokemon
                val src = sourceMon ?: state.target as? Pokemon
                if (src == null || !p.isAdjacent(src)) return@on Unit
                if (!p.knownType || p.hasType("Steel")) p.maybeTrapped = true
                Unit
            }
        }
        ability("magicguard") {
            on("Damage") {
                val e = sourceEffect
                if (e?.effectType != "Move") {
                    if (e?.effectType == "Ability") add("-activate", source, "ability: " + e.name)
                    false
                } else Unit
            }
        }
        ability("toxicchain") {
            on("SourceDamagingHit") {
                val t = pokemon
                if (t.hasAbility("shielddust") || t.hasItem("covertcloak")) return@on Unit
                if (randomChance(3, 10)) t.trySetStatus("tox", sourceMon)
                Unit
            }
        }
        ability("aromaveil") {
            on("AllyTryAddVolatile") {
                val status = relay as EffectLike
                if (status.id in listOf("attract", "disable", "encore", "healblock", "taunt", "torment")) {
                    if (sourceEffect?.effectType == "Move") {
                        add("-block", target, "ability: Aroma Veil", "[of] ${holder()}")
                    }
                    null
                } else Unit
            }
        }
        ability("shielddust") {
            on("ModifySecondaries") {
                @Suppress("UNCHECKED_CAST")
                (relay as List<HitData>).filter { it.hitSelf != null || Js.truthy(it.data("dustproof")) }
            }
        }
        ability("asoneglastrier") {
            on("PreStart") {
                add("-ability", pokemon, "As One")
                add("-ability", pokemon, "Unnerve")
                state["unnerved"] = true
                Unit
            }
            on("End") { state["unnerved"] = false; Unit }
            on("FoeTryEatItem") { !Js.truthy(state["unnerved"]) }
            on("SourceAfterFaint") {
                if (sourceEffect?.effectType == "Move") {
                    boost(mapOf("atk" to relayInt), sourceMon, sourceMon, dex.ability("chillingneigh"))
                }
                Unit
            }
        }
        ability("unnerve") {
            on("PreStart") {
                add("-ability", pokemon, "Unnerve")
                state["unnerved"] = true
                Unit
            }
            on("Start") {
                if (Js.truthy(state["unnerved"])) return@on Unit
                add("-ability", pokemon, "Unnerve")
                state["unnerved"] = true
                Unit
            }
            on("End") { state["unnerved"] = false; Unit }
            on("FoeTryEatItem") { !Js.truthy(state["unnerved"]) }
        }
        ability("battery") {
            on("AllyBasePower") {
                if (target !== state.target && move.category == "Special") chainModify(intArrayOf(5325, 4096)) else Unit
            }
        }
        ability("powerspot") {
            on("AllyBasePower") { if (target !== state.target) chainModify(intArrayOf(5325, 4096)) else Unit }
        }
        ability("eartheater") {
            on("TryHit") {
                val t = pokemon
                if (t !== source && move.type == "Ground") {
                    if (!Js.truthy(battle.heal(t.baseMaxhp / 4.0))) add("-immune", t, "[from] ability: Earth Eater")
                    null
                } else Unit
            }
        }
        ability("hungerswitch") {
            on("Residual") {
                val p = pokemon
                if (p.species.baseSpecies != "Morpeko" || p.terastallized != null) return@on Unit
                val targetForme = if (p.species.name == "Morpeko") "Morpeko-Hangry" else "Morpeko"
                p.formeChange(targetForme)
                Unit
            }
        }
        ability("myceliummight") {
            on("FractionalPriority") { if (move.category == "Status") -0.1 else Unit }
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.category == "Status") m.ignoreAbility = true
                Unit
            }
        }
        ability("swordofruin") {
            on("Start") {
                if (battle.suppressingAbility(pokemon)) return@on Unit
                add("-ability", pokemon, "Sword of Ruin")
                Unit
            }
            on("AnyModifyDef") {
                val abilityHolder = holder()
                if (pokemon.hasAbility("Sword of Ruin")) return@on Unit
                val m = move
                if ((m.extra["ruinedDef"] as? Pokemon)?.hasAbility("Sword of Ruin") != true) m.extra["ruinedDef"] = abilityHolder
                if (m.extra["ruinedDef"] !== abilityHolder) return@on Unit
                chainModify(0.75)
            }
        }
        ability("transistor") {
            on("ModifyAtk") { if (move.type == "Electric") chainModify(intArrayOf(5325, 4096)) else Unit }
            on("ModifySpA") { if (move.type == "Electric") chainModify(intArrayOf(5325, 4096)) else Unit }
        }
        ability("zerotohero") {
            on("SwitchOut") {
                val p = pokemon
                if (p.baseSpecies.baseSpecies != "Palafin") return@on Unit
                if (p.species.forme != "Hero") p.formeChange("Palafin-Hero", self, true)
                Unit
            }
            on("SwitchIn") { state["switchingIn"] = true; Unit }
            on("Start") {
                val p = pokemon
                if (!Js.truthy(state["switchingIn"])) return@on Unit
                state["switchingIn"] = false
                if (p.baseSpecies.baseSpecies != "Palafin") return@on Unit
                if (!Js.truthy(state["heroMessageDisplayed"]) && p.species.forme == "Hero") {
                    add("-activate", p, "ability: Zero to Hero")
                    state["heroMessageDisplayed"] = true
                }
                Unit
            }
        }
        ability("electromorphosis") {
            on("DamagingHit") { pokemon.addVolatile("charge"); Unit }
        }
        ability("imposter") {
            on("SwitchIn") { state["switchingIn"] = true; Unit }
            on("Start") {
                val p = pokemon
                if (!Js.truthy(state["switchingIn"])) return@on Unit
                val foeActive = p.side.foe.active
                val t = foeActive.getOrNull(foeActive.size - 1 - p.position)
                if (t != null) p.transformInto(t, dex.ability("imposter"))
                state["switchingIn"] = false
                Unit
            }
        }
        ability("sniper") {
            on("ModifyDamage") { if (sourceMon!!.getMoveHitData(move).crit) chainModify(1.5) else Unit }
        }
        ability("rattled") {
            on("DamagingHit") {
                if (move.type in listOf("Dark", "Bug", "Ghost")) boost(mapOf("spe" to 1))
                Unit
            }
            on("AfterBoost") {
                if (sourceEffect?.name == "Intimidate") boost(mapOf("spe" to 1))
                Unit
            }
        }
        ability("toxicdebris") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                val side = if (src.isAlly(t)) src.side.foe else src.side
                val toxicSpikes = side.sideConditions["toxicspikes"]
                if (move.category == "Physical" && (toxicSpikes == null || Js.num(toxicSpikes["layers"]) < 2)) {
                    add("-activate", t, "ability: Toxic Debris")
                    side.addSideCondition("toxicspikes", t)
                }
                Unit
            }
        }
        ability("icescales") {
            on("SourceModifyDamage") { if (move.category == "Special") chainModify(0.5) else Unit }
        }
        ability("trace") {
            on("Start") {
                val p = pokemon
                if (p.adjacentFoes().any { it.ability == "noability" }) state["gaveUp"] = true
                if (p.hasItem("Ability Shield")) {
                    add("-block", p, "item: Ability Shield")
                    state["gaveUp"] = true
                }
                Unit
            }
            on("Update") {
                val p = pokemon
                if (!p.isStarted || Js.truthy(state["gaveUp"])) return@on Unit
                val possibleTargets = p.adjacentFoes().filter { !it.getAbility().flag("notrace") && it.ability != "noability" }
                if (possibleTargets.isEmpty()) return@on Unit
                val t = battle.sample(possibleTargets)
                val ability = t.getAbility()
                if (Js.truthy(p.setAbility(ability.id))) {
                    add("-ability", p, ability, "[from] ability: Trace", "[of] $t")
                }
                Unit
            }
        }
        ability("thermalexchange") {
            on("DamagingHit") {
                if (move.type == "Fire") boost(mapOf("atk" to 1))
                Unit
            }
            burnCure(this, "Thermal Exchange")
        }
        ability("waterveil") {
            burnCure(this, "Water Veil")
        }
        ability("solidrock") {
            on("SourceModifyDamage") { if (sourceMon!!.getMoveHitData(move).typeMod > 0) chainModify(0.75) else Unit }
        }
        ability("supersweetsyrup") {
            on("Start") {
                val p = pokemon
                if (p.syrupTriggered) return@on Unit
                p.syrupTriggered = true
                add("-ability", p, "Supersweet Syrup")
                var activated = false
                for (t in p.adjacentFoes()) {
                    if (!activated) {
                        add("-ability", p, "Supersweet Syrup", "boost")
                        activated = true
                    }
                    if (t.volatiles["substitute"] != null) add("-immune", t)
                    else battle.boost(mapOf("evasion" to -1), t, p, null, true)
                }
                Unit
            }
        }
        ability("hustle") {
            on("ModifyAtk") { modify(relayInt, 1.5) }
            on("SourceModifyAccuracy") {
                if (move.category == "Physical" && Js.isNumber(relay)) chainModify(intArrayOf(3277, 4096)) else Unit
            }
        }
        ability("keeneye") {
            on("TryBoost") {
                if (source != null && target === source) return@on Unit
                val b = boosts(relay)
                val acc = b["accuracy"]
                if (acc != null && acc != 0 && acc < 0) {
                    b.remove("accuracy")
                    val e = sourceEffect
                    val hasSecondaries = (e as? ActiveMove)?.secondaries != null || (e != null && e !is ActiveMove && e.data("secondaries") != null)
                    if (!hasSecondaries) add("-fail", target, "unboost", "accuracy", "[from] ability: Keen Eye", "[of] $target")
                }
                Unit
            }
            on("ModifyMove") { (relay as ActiveMove).ignoreEvasion = true; Unit }
        }
        ability("heatproof") {
            on("SourceModifyAtk") { if (move.type == "Fire") chainModify(0.5) else Unit }
            on("SourceModifySpA") { if (move.type == "Fire") chainModify(0.5) else Unit }
            on("Damage") { if (sourceEffect?.id == "brn") Js.number(relayNum / 2) else Unit }
        }
        ability("liquidvoice") {
            on("ModifyType") {
                val m = relay as ActiveMove
                if (m.flag("sound") && pokemon.volatiles["dynamax"] == null) m.type = "Water"
                Unit
            }
        }
        ability("sweetveil") {
            on("AllySetStatus") {
                if ((relay as EffectLike).id == "slp") {
                    add("-block", target, "ability: Sweet Veil", "[of] ${holder()}")
                    null
                } else Unit
            }
            on("AllyTryAddVolatile") {
                if ((relay as EffectLike).id == "yawn") {
                    add("-block", target, "ability: Sweet Veil", "[of] ${holder()}")
                    null
                } else Unit
            }
        }
        ability("raindish") {
            on("Weather") {
                val t = pokemon
                if (t.hasItem("utilityumbrella")) return@on Unit
                val id = sourceEffect?.id
                if (id == "raindance" || id == "primordialsea") heal(t.baseMaxhp / 16.0)
                Unit
            }
        }
        ability("marvelscale") {
            on("ModifyDef") { if (pokemon.status.isNotEmpty()) chainModify(1.5) else Unit }
        }
        ability("surgesurfer") {
            on("ModifySpe") { if (field.isTerrain("electricterrain")) chainModify(2) else Unit }
        }
        priorityBlock("dazzling", "Dazzling")
        priorityBlock("queenlymajesty", "Queenly Majesty")
        ability("purepower") {
            on("ModifyAtk") { chainModify(2) }
        }
        ability("stench") {
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
        typeBoostOnHit("steamengine", listOf("Water", "Fire"), "spe", 6)
        typeBoostOnHit("watercompaction", listOf("Water"), "def", 2)
        ability("shedskin") {
            on("Residual") {
                val p = pokemon
                if (p.hp != 0 && p.status.isNotEmpty() && randomChance(33, 100)) {
                    add("-activate", p, "ability: Shed Skin")
                    p.cureStatus()
                }
                Unit
            }
        }
        ability("effectspore") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (battle.checkMoveMakesContact(move, src, t) && src.status.isEmpty() && src.runStatusImmunity("powder")) {
                    val r = random(100)
                    if (r < 11) src.setStatus("slp", t)
                    else if (r < 21) src.setStatus("par", t)
                    else if (r < 30) src.setStatus("psn", t)
                }
                Unit
            }
        }
        ability("steadfast") {
            on("Flinch") { boost(mapOf("spe" to 1)); Unit }
        }
        ability("gluttony") {
            on("Start") { pokemon.abilityState["gluttony"] = true; Unit }
            on("Damage") { pokemon.abilityState["gluttony"] = true; Unit }
        }
        ability("plus") {
            on("ModifySpA") {
                for (allyActive in pokemon.allies()) {
                    if (allyActive.hasAbility(listOf("minus", "plus"))) return@on chainModify(1.5)
                }
                Unit
            }
        }
        ability("suctioncups") {
            on("DragOut") {
                add("-activate", pokemon, "ability: Suction Cups")
                null
            }
        }
        ability("rivalry") {
            on("BasePower") {
                val attacker = pokemon
                val defender = sourceMon!!
                if (attacker.gender.isNotEmpty() && defender.gender.isNotEmpty()) {
                    if (attacker.gender == defender.gender) chainModify(1.25) else chainModify(0.75)
                } else Unit
            }
        }
        ability("mistysurge") {
            on("Start") { field.setTerrain("mistyterrain"); Unit }
        }
        // Misty Surge's terrain, which no other batch has ported yet.
        move("mistyterrain") {
            condition {
                callback("durationCallback") { if ((target as? Pokemon)?.hasItem("terrainextender") == true) 8 else 5 }
                on("SetStatus") {
                    val t = pokemon
                    if (t.isGrounded() != true || t.isSemiInvulnerable()) return@on Unit
                    val e = effect
                    if (e != null && (Js.truthy(effectStatus(e)) || (e as? EffectLike)?.id == "yawn")) add("-activate", t, "move: Misty Terrain")
                    false
                }
                on("TryAddVolatile") {
                    val t = pokemon
                    if (t.isGrounded() != true || t.isSemiInvulnerable()) return@on Unit
                    if ((relay as EffectLike).id == "confusion") {
                        val e = sourceEffect
                        val hasSecondaries = (e as? ActiveMove)?.secondaries != null || (e != null && e !is ActiveMove && e.data("secondaries") != null)
                        if (e?.effectType == "Move" && !hasSecondaries) add("-activate", t, "move: Misty Terrain")
                        null
                    } else Unit
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
        ability("opportunist") {
            on("FoeAfterBoost") {
                val e = sourceEffect
                if (e?.name == "Opportunist" || e?.name == "Mirror Herb") return@on Unit
                val p = holder()
                val positiveBoosts = LinkedHashMap<String, Int>()
                for ((i, v) in boosts(relay)) if (v > 0) positiveBoosts[i] = v
                if (positiveBoosts.isEmpty()) return@on Unit
                boost(positiveBoosts, p)
                Unit
            }
        }
        ability("anticipation") {
            on("Start") {
                val p = pokemon
                for (t in p.foes()) {
                    for (slot in t.moveSlots) {
                        val m = dex.move(slot.move) ?: continue
                        if (m.category == "Status") continue
                        val moveType = if (m.id == "hiddenpower") t.hpType else m.type
                        val types = p.getTypes()
                        if ((dex.notImmune(moveType, types) && types.sumOf { dex.effectiveness(moveType, it) } > 0) ||
                            Js.truthy(m.data("ohko"))) {
                            add("-ability", p, "Anticipation")
                            return@on Unit
                        }
                    }
                }
                Unit
            }
        }
        ability("pickup") {
            on("Residual") {
                val p = pokemon
                if (p.item.isNotEmpty()) return@on Unit
                val pickupTargets = battle.getAllActive().filter { it.lastItem.isNotEmpty() && it.usedItemThisTurn && p.isAdjacent(it) }
                if (pickupTargets.isEmpty()) return@on Unit
                val randomTarget = battle.sample(pickupTargets)
                val item = randomTarget.lastItem
                randomTarget.lastItem = ""
                add("-item", p, dex.item(item), "[from] ability: Pickup")
                p.setItem(item)
                Unit
            }
        }
        ability("ripen") {
            on("TryHeal") {
                val e = sourceEffect ?: return@on Unit
                if (e.name == "Berry Juice" || e.name == "Leftovers") add("-activate", target, "ability: Ripen")
                if (Js.truthy(e.data("isBerry"))) chainModify(2) else Unit
            }
            on("ChangeBoost") {
                val e = sourceEffect
                if (e != null && Js.truthy(e.data("isBerry"))) {
                    val b = boosts(relay)
                    for (key in b.keys.toList()) b[key] = b.getValue(key) * 2
                }
                Unit
            }
            on("SourceModifyDamage") {
                val t = sourceMon!!
                if (Js.truthy(t.abilityState["berryWeaken"])) {
                    t.abilityState["berryWeaken"] = false
                    chainModify(0.5)
                } else Unit
            }
            on("TryEatItem") { add("-activate", pokemon, "ability: Ripen"); Unit }
            on("EatItem") {
                val weakenBerries = listOf("Babiri Berry", "Charti Berry", "Chilan Berry", "Chople Berry", "Coba Berry", "Colbur Berry",
                    "Haban Berry", "Kasib Berry", "Kebia Berry", "Occa Berry", "Passho Berry", "Payapa Berry", "Rindo Berry",
                    "Roseli Berry", "Shuca Berry", "Tanga Berry", "Wacan Berry", "Yache Berry")
                pokemon.abilityState["berryWeaken"] = (relay as EffectLike).name in weakenBerries
                Unit
            }
        }
        ability("aerilate") {
            on("ModifyType") {
                val m = relay as ActiveMove
                val noModifyType = listOf("judgment", "multiattack", "naturalgift", "revelationdance", "technoblast", "terrainpulse", "weatherball")
                if (m.type == "Normal" && m.id !in noModifyType && !(Js.truthy(m.isZ) && m.category != "Status") &&
                    !(m.name == "Tera Blast" && pokemon.terastallized != null)) {
                    m.type = "Flying"
                    m.typeChangerBoosted = self
                }
                Unit
            }
            on("BasePower") { if (move.typeChangerBoosted === self) chainModify(intArrayOf(4915, 4096)) else Unit }
        }
        ability("beastboost") {
            on("SourceAfterFaint") {
                if (sourceEffect?.effectType == "Move") {
                    val src = sourceMon!!
                    val bestStat = src.getBestStat(true, true)
                    boost(mapOf(bestStat to relayInt), src)
                }
                Unit
            }
        }
        ability("cottondown") {
            on("DamagingHit") {
                val t = pokemon
                var activated = false
                for (p in battle.getAllActive()) {
                    if (p === t || p.fainted) continue
                    if (!activated) {
                        add("-ability", t, "Cotton Down")
                        activated = true
                    }
                    battle.boost(mapOf("spe" to -1), p, t, null, true)
                }
                Unit
            }
        }
        ability("deltastream") {
            on("Start") { field.setWeather("deltastream"); Unit }
            on("AnySetWeather") {
                val strongWeathers = listOf("desolateland", "primordialsea", "deltastream")
                if (field.getWeather().id == "deltastream" && sourceEffect?.id !in strongWeathers) false else Unit
            }
            on("End") {
                val p = pokemon
                if (field.weatherState.source !== p) return@on Unit
                for (t in battle.getAllActive()) {
                    if (t === p) continue
                    if (t.hasAbility("deltastream")) {
                        field.weatherState.source = t
                        return@on Unit
                    }
                }
                field.clearWeather()
                Unit
            }
        }
        ability("embodyaspecthearthflame") {
            on("Start") {
                val p = pokemon
                if (p.baseSpecies.name == "Ogerpon-Hearthflame-Tera" && !Js.truthy(state["embodied"])) {
                    state["embodied"] = true
                    boost(mapOf("atk" to 1), p)
                }
                Unit
            }
            on("SwitchIn") { state.remove("embodied"); Unit }
        }
        ability("emergencyexit") {
            on("EmergencyExit") {
                val t = pokemon
                if (battle.canSwitch(t.side) == 0 || t.forceSwitchFlag || Js.truthy(t.switchFlag)) return@on Unit
                for (side in battle.sides) {
                    for (active in side.active) active?.switchFlag = false
                }
                t.switchFlag = true
                add("-activate", t, "ability: Emergency Exit")
                Unit
            }
        }
        ability("forecast") {
            on("Start") { battle.singleEvent("WeatherChange", self, state, pokemon); Unit }
            on("WeatherChange") {
                val p = pokemon
                if (p.baseSpecies.baseSpecies != "Castform" || p.transformed) return@on Unit
                var forme: String? = null
                when (p.effectiveWeather()) {
                    "sunnyday", "desolateland" -> if (p.species.id != "castformsunny") forme = "Castform-Sunny"
                    "raindance", "primordialsea" -> if (p.species.id != "castformrainy") forme = "Castform-Rainy"
                    "hail", "snow" -> if (p.species.id != "castformsnowy") forme = "Castform-Snowy"
                    else -> if (p.species.id != "castform") forme = "Castform"
                }
                if (p.isActive && forme != null) p.formeChange(forme, self, true, "[msg]")
                Unit
            }
        }
        ability("grasspelt") {
            on("ModifyDef") { if (field.isTerrain("grassyterrain")) chainModify(1.5) else Unit }
        }
        contactAbilitySwap("lingeringaroma", "Lingering Aroma")
        contactAbilitySwap("mummy", "Mummy")
        ability("parentalbond") {
            on("PrepareHit") {
                val m = move
                if (m.category == "Status" || Js.truthy(m.multihit) || m.flag("noparentalbond") || m.flag("charge") ||
                    m.flag("futuremove") || m.spreadHit || Js.truthy(m.isZ) || Js.truthy(m.isMax)) return@on Unit
                m.multihit = 2
                m.multihitType = "parentalbond"
                Unit
            }
            on("SourceModifySecondaries") {
                val m = effect as? ActiveMove ?: return@on Unit
                if (m.multihitType == "parentalbond" && m.id == "secretpower" && m.hit < 2) {
                    @Suppress("UNCHECKED_CAST")
                    (relay as List<HitData>).filter { it.hitVolatileStatus == "flinch" }
                } else Unit
            }
        }
        ability("piercingdrill") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.flag("contact")) {
                    m.flags.remove("protect")
                    m.extra["onBasePowerPriority"] = 10
                    val fn: HookFn = {
                        val t = sourceMon!!
                        val protectVolatiles = listOf("protect", "kingsshield", "spikyshield", "banefulbunker", "obstruct", "silktrap", "matblock")
                        val protectSideConditions = listOf("quickguard", "wideguard", "craftyshield")
                        if (protectVolatiles.any { t.volatiles[it] != null } || protectSideConditions.any { t.side.sideConditions[it] != null }) {
                            chainModify(0.25)
                        } else Unit
                    }
                    m.extra["onBasePower"] = fn
                }
                Unit
            }
        }
        ability("receiver") {
            on("AllyFaint") {
                val me = holder()
                if (me.hp == 0) return@on Unit
                val t = pokemon
                val ability = t.getAbility()
                if (ability.flag("noreceiver") || ability.id == "noability") return@on Unit
                if (Js.truthy(me.setAbility(ability.id))) {
                    add("-ability", me, ability, "[from] ability: Receiver", "[of] $t")
                }
                Unit
            }
        }
        ability("screencleaner") {
            on("Start") {
                val p = pokemon
                var activated = false
                for (sideCondition in listOf("reflect", "lightscreen", "auroraveil")) {
                    for (side in listOf(p.side) + p.side.foeSidesWithConditions()) {
                        if (side.getSideCondition(sideCondition) != null) {
                            if (!activated) {
                                add("-activate", p, "ability: Screen Cleaner")
                                activated = true
                            }
                            side.removeSideCondition(sideCondition)
                        }
                    }
                }
                Unit
            }
        }
        ability("stancechange") {
            on("ModifyMove") {
                val m = relay as ActiveMove
                val attacker = pokemon
                if (attacker.species.baseSpecies != "Aegislash" || attacker.transformed) return@on Unit
                if (m.category == "Status" && m.id != "kingsshield") return@on Unit
                val targetForme = if (m.id == "kingsshield") "Aegislash" else "Aegislash-Blade"
                if (attacker.species.name != targetForme) attacker.formeChange(targetForme)
                Unit
            }
        }
        ability("teraformzero") {
            on("AfterTerastallization") {
                val p = pokemon
                if (p.baseSpecies.name != "Terapagos-Stellar") return@on Unit
                if (field.weather.isNotEmpty() || field.terrain.isNotEmpty()) {
                    add("-ability", p, "Teraform Zero")
                    field.clearWeather()
                    field.clearTerrain()
                }
                Unit
            }
        }
        ability("wanderingspirit") {
            on("DamagingHit") {
                val t = pokemon
                val src = sourceMon!!
                if (src.getAbility().flag("failskillswap") || t.volatiles["dynamax"] != null) return@on Unit
                if (battle.checkMoveMakesContact(move, src, t)) {
                    val targetCanBeSet = battle.runEvent("SetAbility", t, src, self, src.ability)
                    if (!Js.truthy(targetCanBeSet)) return@on targetCanBeSet
                    val sourceAbility = src.setAbility("wanderingspirit", t)
                    if (!Js.truthy(sourceAbility)) return@on Unit
                    if (t.isAlly(src)) {
                        add("-activate", t, "Skill Swap", "", "", "[of] $src")
                    } else {
                        add("-activate", t, "ability: Wandering Spirit", dex.ability(sourceAbility as String).name, "Wandering Spirit", "[of] $src")
                    }
                    t.setAbility(sourceAbility as String)
                }
                Unit
            }
        }
        ability("wonderskin") {
            on("ModifyAccuracy") { if (move.category == "Status" && Js.isNumber(relay)) 50 else Unit }
        }
    }

    private fun HookRegistrar.breaker(id: String, name: String) = ability(id) {
        on("Start") { add("-ability", pokemon, name); Unit }
        on("ModifyMove") { (relay as ActiveMove).ignoreAbility = true; Unit }
    }

    /** Drizzle-style: set the weather unless a Primal Reversion of [primal] is about to run. */
    private fun HookRegistrar.weatherSetter(id: String, weather: String, primal: String) = ability(id) {
        on("Start") {
            val src = pokemon
            for (action in battle.queue.list) {
                if (action.choice == "runPrimal" && action.pokemon === src && src.species.id == primal) return@on Unit
                if (action.choice != "runSwitch" && action.choice != "runPrimal") break
            }
            field.setWeather(weather)
            Unit
        }
    }

    private fun HookRegistrar.pinch(id: String, type: String) = ability(id) {
        on("ModifyAtk") { if (move.type == type && pokemon.hp <= pokemon.maxhp / 3.0) chainModify(1.5) else Unit }
        on("ModifySpA") { if (move.type == type && pokemon.hp <= pokemon.maxhp / 3.0) chainModify(1.5) else Unit }
    }

    private fun HookRegistrar.faintBoost(id: String, stat: String) = ability(id) {
        on("SourceAfterFaint") {
            if (sourceEffect?.effectType == "Move") boost(mapOf(stat to relayInt), sourceMon)
            Unit
        }
    }

    private fun HookRegistrar.fullHpHalver(id: String) = ability(id) {
        on("SourceModifyDamage") {
            val t = sourceMon!!
            if (t.hp >= t.maxhp) chainModify(0.5) else Unit
        }
    }

    private fun HookRegistrar.typeBoostOnHit(id: String, types: List<String>, stat: String, amount: Int) = ability(id) {
        on("DamagingHit") {
            if (move.type in types) boost(mapOf(stat to amount))
            Unit
        }
    }

    private fun HookRegistrar.priorityBlock(id: String, name: String) = ability(id) {
        on("FoeTryMove") {
            val m = move
            val targetAllExceptions = listOf("perishsong", "flowershield", "rototiller")
            if (m.target == "foeSide" || (m.target == "all" && m.id !in targetAllExceptions)) return@on Unit
            val dazzlingHolder = state.target as Pokemon
            if ((sourceMon!!.isAlly(dazzlingHolder) || m.target == "all") && m.priority > 0.1) {
                battle.attrLastMove("[still]")
                add("cant", dazzlingHolder, "ability: $name", m, "[of] $target")
                false
            } else Unit
        }
    }

    private fun HookRegistrar.contactAbilitySwap(id: String, name: String) = ability(id) {
        on("DamagingHit") {
            val t = pokemon
            val src = sourceMon!!
            val sourceAbility = src.getAbility()
            if (sourceAbility.flag("cantsuppress") || sourceAbility.id == id) return@on Unit
            if (battle.checkMoveMakesContact(move, src, t, !src.isAlly(t))) {
                val oldAbility = src.setAbility(id, t)
                if (Js.truthy(oldAbility)) {
                    add("-activate", t, "ability: $name", dex.ability(oldAbility as String).name, "[of] $src")
                }
            }
            Unit
        }
    }

    /** Water Veil and Thermal Exchange: cure and block burns. */
    private fun burnCure(hooks: EffectHooks, name: String) = with(hooks) {
        on("Update") {
            val p = pokemon
            if (p.status == "brn") {
                add("-activate", p, "ability: $name")
                p.cureStatus()
            }
            Unit
        }
        on("SetStatus") {
            if ((relay as EffectLike).id != "brn") return@on Unit
            if (Js.truthy(effectStatus(effect))) add("-immune", target, "[from] ability: $name")
            false
        }
    }
}
