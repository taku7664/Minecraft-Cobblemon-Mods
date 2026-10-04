package jbro.cobblemon.mcc.betterai.engine.effects

import com.google.gson.JsonObject
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/** Port of `data/conditions.js`: statuses, volatiles the core relies on, and weather. */
object BaseConditions : HookSet() {
    /** Confusion's self-hit is dealt as a pseudo-move with id `confused`. */
    val CONFUSED = Effect("confused", "confused", "Move", JsonObject(), "", emptySet())

    override fun HookRegistrar.define() {
        condition("brn") {
            on("Start") {
                val se = sourceEffect
                when {
                    se != null && se.id == "flameorb" -> add("-status", target, "brn", "[from] item: Flame Orb")
                    se != null && se.effectType == "Ability" -> add("-status", target, "brn", "[from] ability: " + se.name, "[of] $source")
                    else -> add("-status", target, "brn")
                }
                Unit
            }
            on("Residual") { damage(pokemon.baseMaxhp / 16.0); Unit }
        }
        condition("par") {
            on("Start") {
                val se = sourceEffect
                if (se != null && se.effectType == "Ability") add("-status", target, "par", "[from] ability: " + se.name, "[of] $source")
                else add("-status", target, "par")
                Unit
            }
            on("ModifySpe") {
                var spe = battle.finalModify(relayInt)
                if (!pokemon.hasAbility("quickfeet")) spe = Math.floor(spe * 50 / 100.0).toInt()
                spe
            }
            on("BeforeMove") {
                if (randomChance(1, 4)) {
                    add("cant", pokemon, "par")
                    false
                } else Unit
            }
        }
        condition("slp") {
            on("Start") {
                val se = sourceEffect
                when {
                    se != null && se.effectType == "Ability" -> add("-status", target, "slp", "[from] ability: " + se.name, "[of] $source")
                    se != null && se.effectType == "Move" -> add("-status", target, "slp", "[from] move: " + se.name)
                    else -> add("-status", target, "slp")
                }
                state["startTime"] = random(2, 5)
                state["time"] = state["startTime"]
                if (pokemon.removeVolatile("nightmare")) add("-end", target, "Nightmare", "[silent]")
                Unit
            }
            on("BeforeMove") {
                val p = pokemon
                if (p.hasAbility("earlybird")) p.statusState["time"] = p.statusState.int("time") - 1
                p.statusState["time"] = p.statusState.int("time") - 1
                if (p.statusState.int("time") <= 0) {
                    p.cureStatus()
                    return@on Unit
                }
                add("cant", p, "slp")
                if (move.sleepUsable) return@on Unit
                false
            }
        }
        condition("frz") {
            on("Start") {
                val se = sourceEffect
                if (se != null && se.effectType == "Ability") add("-status", target, "frz", "[from] ability: " + se.name, "[of] $source")
                else add("-status", target, "frz")
                if (pokemon.species.name == "Shaymin-Sky" && pokemon.baseSpecies.baseSpecies == "Shaymin") {
                    pokemon.formeChange("Shaymin", battle.effect, true)
                }
                Unit
            }
            on("BeforeMove") {
                if (move.flag("defrost")) return@on Unit
                if (randomChance(1, 5)) {
                    pokemon.cureStatus()
                    return@on Unit
                }
                add("cant", pokemon, "frz")
                false
            }
            on("ModifyMove") {
                val m = relay as ActiveMove
                if (m.flag("defrost")) {
                    add("-curestatus", pokemon, "frz", "[from] move: $m")
                    pokemon.clearStatus()
                }
                Unit
            }
            on("AfterMoveSecondary") {
                if (move.thawsTarget) pokemon.cureStatus()
                Unit
            }
            on("DamagingHit") {
                if (move.type == "Fire" && move.category != "Status") pokemon.cureStatus()
                Unit
            }
        }
        condition("psn") {
            on("Start") {
                val se = sourceEffect
                if (se != null && se.effectType == "Ability") add("-status", target, "psn", "[from] ability: " + se.name, "[of] $source")
                else add("-status", target, "psn")
                Unit
            }
            on("Residual") { damage(pokemon.baseMaxhp / 8.0); Unit }
        }
        condition("tox") {
            on("Start") {
                state["stage"] = 0
                val se = sourceEffect
                when {
                    se != null && se.id == "toxicorb" -> add("-status", target, "tox", "[from] item: Toxic Orb")
                    se != null && se.effectType == "Ability" -> add("-status", target, "tox", "[from] ability: " + se.name, "[of] $source")
                    else -> add("-status", target, "tox")
                }
                Unit
            }
            on("SwitchIn") { state["stage"] = 0; Unit }
            on("Residual") {
                if (state.int("stage") < 15) state["stage"] = state.int("stage") + 1
                damage(Js.clampIntRange(pokemon.baseMaxhp / 16.0, 1) * state.int("stage"))
                Unit
            }
        }
        condition("confusion") {
            on("Start") {
                val se = sourceEffect
                when {
                    se?.id == "lockedmove" -> add("-start", target, "confusion", "[fatigue]")
                    se?.effectType == "Ability" -> add("-start", target, "confusion", "[from] ability: " + se.name, "[of] $source")
                    else -> add("-start", target, "confusion")
                }
                val min = if (se?.id == "axekick") 3 else 2
                state["time"] = random(min, 6)
                Unit
            }
            on("End") { add("-end", target, "confusion"); Unit }
            on("BeforeMove") {
                val p = pokemon
                val volatile = p.volatiles.getValue("confusion")
                volatile["time"] = volatile.int("time") - 1
                if (volatile.int("time") == 0) {
                    p.removeVolatile("confusion")
                    return@on Unit
                }
                add("-activate", p, "confusion")
                if (!randomChance(33, 100)) return@on Unit
                battle.activeTarget = p
                val dmg = battle.actions.getConfusionDamage(p, 40)
                battle.damage(dmg, p, p, CONFUSED)
                false
            }
        }
        condition("flinch") {
            on("BeforeMove") {
                add("cant", pokemon, "flinch")
                battle.runEvent("Flinch", pokemon)
                false
            }
        }
        condition("trapped") {
            on("TrapPokemon") { pokemon.tryTrap(); Unit }
            on("Start") { add("-activate", target, "trapped"); Unit }
        }
        condition("partiallytrapped") {
            callback("durationCallback") {
                if (sourceMon?.hasItem("gripclaw") == true) 8 else random(5, 7)
            }
            on("Start") {
                add("-activate", target, "move: " + state.sourceEffect?.name, "[of] $source")
                state["boundDivisor"] = if (sourceMon?.hasItem("bindingband") == true) 6 else 8
                Unit
            }
            on("Residual") {
                val src = state.source
                val gmaxEffect = state.sourceEffect?.id in listOf("gmaxcentiferno", "gmaxsandblast")
                if (src != null && (!src.isActive || src.hp <= 0 || src.activeTurns == 0) && !gmaxEffect) {
                    pokemon.volatiles.remove("partiallytrapped")
                    add("-end", pokemon, state.sourceEffect, "[partiallytrapped]", "[silent]")
                    return@on Unit
                }
                damage(pokemon.baseMaxhp / state.int("boundDivisor").toDouble())
                Unit
            }
            on("End") { add("-end", pokemon, state.sourceEffect, "[partiallytrapped]"); Unit }
            on("TrapPokemon") {
                val gmaxEffect = state.sourceEffect?.id in listOf("gmaxcentiferno", "gmaxsandblast")
                if (state.source?.isActive == true || gmaxEffect) pokemon.tryTrap()
                Unit
            }
        }
        condition("lockedmove") {
            on("Residual") {
                if (pokemon.status == "slp") pokemon.volatiles.remove("lockedmove")
                state["trueDuration"] = state.int("trueDuration") - 1
                Unit
            }
            on("Start") {
                state["trueDuration"] = random(2, 4)
                state["move"] = sourceEffect?.id
                Unit
            }
            on("Restart") {
                if (state.int("trueDuration") >= 2) state.duration = 2
                Unit
            }
            on("End") {
                if (state.int("trueDuration") > 1) return@on Unit
                pokemon.addVolatile("confusion")
                Unit
            }
            on("LockMove") {
                if (pokemon.volatiles["dynamax"] != null) return@on Unit
                state["move"]
            }
        }
        condition("twoturnmove") {
            on("Start") {
                val attacker = pokemon
                var defender = sourceMon
                val effect = sourceEffect!!
                state["move"] = effect.id
                attacker.addVolatile(effect.id)
                var moveTargetLoc = attacker.lastMoveTargetLoc
                val fromOther = (effect as? ActiveMove)?.sourceEffect?.isNotEmpty() == true
                if (fromOther && dex.move(effect.id)?.target != "self") {
                    if (defender == null || defender.fainted) defender = battle.sample(attacker.foes(true))
                    moveTargetLoc = attacker.getLocOf(defender)
                }
                attacker.volatiles[effect.id]?.set("targetLoc", moveTargetLoc)
                battle.attrLastMove("[still]")
                battle.runEvent("PrepareHit", attacker, defender, effect)
                Unit
            }
            on("End") { pokemon.removeVolatile(state["move"] as String); Unit }
            on("LockMove") { state["move"] }
            on("MoveAborted") { pokemon.removeVolatile("twoturnmove"); Unit }
        }
        condition("choicelock") {
            on("Start") {
                val active = battle.activeMove ?: error("Battle.activeMove is null")
                if (active.id.isEmpty() || active.hasBounced || active.sourceEffect == "snatch") return@on false
                state["move"] = active.id
                Unit
            }
            on("BeforeMove") {
                val p = pokemon
                if (!p.getItem().bool("isChoice")) {
                    p.removeVolatile("choicelock")
                    return@on Unit
                }
                if (!p.ignoringItem() && p.volatiles["dynamax"] == null && move.id != state["move"] && move.id != "struggle") {
                    battle.addMove("move", p, move.name)
                    battle.attrLastMove("[still]")
                    add("-fail", p)
                    return@on false
                }
                Unit
            }
            on("DisableMove") {
                val p = pokemon
                val locked = state["move"] as? String
                if (!p.getItem().bool("isChoice") || locked == null || p.hasMove(locked) == null) {
                    p.removeVolatile("choicelock")
                    return@on Unit
                }
                if (p.ignoringItem() || p.volatiles["dynamax"] != null) return@on Unit
                for (slot in p.moveSlots) {
                    if (slot.id != locked) p.disableMove(slot.id, false, state.sourceEffect)
                }
                Unit
            }
        }
        condition("mustrecharge") {
            on("BeforeMove") {
                add("cant", pokemon, "recharge")
                pokemon.removeVolatile("mustrecharge")
                pokemon.removeVolatile("truant")
                null
            }
            on("Start") { add("-mustrecharge", pokemon); Unit }
        }
        condition("futuremove") {
            on("End") {
                val data = state
                val targetMon = pokemon
                val move = dex.move(data["move"] as String)!!
                val source = data.source!!
                if (targetMon.fainted || targetMon === source) {
                    hint("${move.name} did not hit because the target is ${if (targetMon.fainted) "fainted" else "the user"}.")
                    return@on Unit
                }
                add("-end", targetMon, "move: " + move.name)
                targetMon.removeVolatile("Protect")
                targetMon.removeVolatile("Endure")
                @Suppress("UNCHECKED_CAST")
                val hitMove = (data["moveData"] as ActiveMove)
                if (source.hasAbility("infiltrator")) hitMove.infiltrates = true
                if (source.hasAbility("normalize")) hitMove.type = "Normal"
                battle.actions.trySpreadMoveHit(mutableListOf(targetMon), source, hitMove, true)
                if (source.isActive && source.hasItem("lifeorb")) {
                    battle.singleEvent("AfterMoveSecondarySelf", source.getItem(), source.itemState, source, targetMon, source.getItem())
                }
                battle.activeMove = null
                battle.checkWin()
                Unit
            }
        }
        condition("healreplacement") {
            on("Start") {
                state.sourceEffect = effect as? EffectLike
                add("-activate", source, "healreplacement")
                Unit
            }
            on("SwitchIn") {
                val t = pokemon
                if (!t.fainted) {
                    t.heal(t.maxhp.toDouble())
                    add("-heal", t, t.getHealth, "[from] move: " + state.sourceEffect?.name, "[zeffect]")
                    t.side.removeSlotCondition(t, "healreplacement")
                }
                Unit
            }
        }
        condition("stall") {
            on("Start") { state["counter"] = 3; Unit }
            on("StallMove") {
                val counter = state.int("counter").takeIf { it != 0 } ?: 1
                val success = randomChance(1, counter)
                if (!success) pokemon.volatiles.remove("stall")
                success
            }
            on("Restart") {
                if (state.int("counter") < Js.int(self.data("counterMax"))) state["counter"] = state.int("counter") * 3
                state.duration = 2
                Unit
            }
        }
        condition("gem") {
            on("BasePower") { chainModify(intArrayOf(5325, 4096)) }
        }
        weather("raindance", "RainDance", "damprock", boost = "Water", suppress = "Fire")
        condition("primordialsea") {
            on("TryMove") {
                if (move.type == "Fire" && move.category != "Status") {
                    add("-fail", pokemon, move, "[from] Primordial Sea")
                    battle.attrLastMove("[still]")
                    null
                } else Unit
            }
            on("WeatherModifyDamage") {
                if ((source as Pokemon).hasItem("utilityumbrella")) return@on Unit
                if (move.type == "Water") return@on chainModify(1.5)
                Unit
            }
            on("FieldStart") { add("-weather", "PrimordialSea", "[from] ability: " + sourceEffect?.name, "[of] $source"); Unit }
            on("FieldResidual") { add("-weather", "PrimordialSea", "[upkeep]"); battle.eachEvent("Weather"); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("sunnyday") {
            callback("durationCallback") { if ((target as? Pokemon)?.hasItem("heatrock") == true) 8 else 5 }
            on("WeatherModifyDamage") {
                val attacker = pokemon
                val defender = source as Pokemon
                if (move.id == "hydrosteam" && !attacker.hasItem("utilityumbrella")) return@on chainModify(1.5)
                if (defender.hasItem("utilityumbrella")) return@on Unit
                if (move.type == "Fire") return@on chainModify(1.5)
                if (move.type == "Water") return@on chainModify(0.5)
                Unit
            }
            on("FieldStart") {
                val se = sourceEffect
                if (se?.effectType == "Ability") add("-weather", "SunnyDay", "[from] ability: " + se.name, "[of] $source")
                else add("-weather", "SunnyDay")
                Unit
            }
            on("Immunity") {
                if (targetMon?.hasItem("utilityumbrella") == true) return@on Unit
                if (relay == "frz") false else Unit
            }
            on("FieldResidual") { add("-weather", "SunnyDay", "[upkeep]"); battle.eachEvent("Weather"); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("desolateland") {
            on("TryMove") {
                if (move.type == "Water" && move.category != "Status") {
                    add("-fail", pokemon, move, "[from] Desolate Land")
                    battle.attrLastMove("[still]")
                    null
                } else Unit
            }
            on("WeatherModifyDamage") {
                if ((source as Pokemon).hasItem("utilityumbrella")) return@on Unit
                if (move.type == "Fire") return@on chainModify(1.5)
                Unit
            }
            on("FieldStart") { add("-weather", "DesolateLand", "[from] ability: " + sourceEffect?.name, "[of] $source"); Unit }
            on("Immunity") {
                if (targetMon?.hasItem("utilityumbrella") == true) return@on Unit
                if (relay == "frz") false else Unit
            }
            on("FieldResidual") { add("-weather", "DesolateLand", "[upkeep]"); battle.eachEvent("Weather"); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("sandstorm") {
            callback("durationCallback") { if ((target as? Pokemon)?.hasItem("smoothrock") == true) 8 else 5 }
            on("ModifySpD") {
                if (pokemon.hasType("Rock") && field.isWeather("sandstorm")) modify(relayInt, 1.5) else Unit
            }
            on("FieldStart") {
                val se = sourceEffect
                if (se?.effectType == "Ability") add("-weather", "Sandstorm", "[from] ability: " + se.name, "[of] $source")
                else add("-weather", "Sandstorm")
                Unit
            }
            on("FieldResidual") {
                add("-weather", "Sandstorm", "[upkeep]")
                if (field.isWeather("sandstorm")) battle.eachEvent("Weather")
                Unit
            }
            on("Weather") { damage(pokemon.baseMaxhp / 16.0); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("hail") {
            callback("durationCallback") { if ((target as? Pokemon)?.hasItem("icyrock") == true) 8 else 5 }
            on("FieldStart") {
                val se = sourceEffect
                if (se?.effectType == "Ability") add("-weather", "Hail", "[from] ability: " + se.name, "[of] $source")
                else add("-weather", "Hail")
                Unit
            }
            on("FieldResidual") {
                add("-weather", "Hail", "[upkeep]")
                if (field.isWeather("hail")) battle.eachEvent("Weather")
                Unit
            }
            on("Weather") { damage(pokemon.baseMaxhp / 16.0); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("snow") {
            callback("durationCallback") { if ((target as? Pokemon)?.hasItem("icyrock") == true) 8 else 5 }
            on("ModifyDef") {
                if (pokemon.hasType("Ice") && field.isWeather("snow")) modify(relayInt, 1.5) else Unit
            }
            on("FieldStart") {
                val se = sourceEffect
                if (se?.effectType == "Ability") add("-weather", "Snow", "[from] ability: " + se.name, "[of] $source")
                else add("-weather", "Snow")
                Unit
            }
            on("FieldResidual") {
                add("-weather", "Snow", "[upkeep]")
                if (field.isWeather("snow")) battle.eachEvent("Weather")
                Unit
            }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("deltastream") {
            on("Effectiveness") {
                val m = activeMove
                if (m != null && m.category != "Status" && source == "Flying" && relayInt > 0) {
                    add("-fieldactivate", "Delta Stream")
                    0
                } else Unit
            }
            on("FieldStart") { add("-weather", "DeltaStream", "[from] ability: " + sourceEffect?.name, "[of] $source"); Unit }
            on("FieldResidual") { add("-weather", "DeltaStream", "[upkeep]"); battle.eachEvent("Weather"); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
        condition("dynamax") {
            on("Start") {
                val p = pokemon
                state["turns"] = 0
                p.removeVolatile("minimize")
                p.removeVolatile("substitute")
                if (p.volatiles["torment"] != null) {
                    p.volatiles.remove("torment")
                    add("-end", p, "Torment", "[silent]")
                }
                if (p.species.id in listOf("cramorantgulping", "cramorantgorging") && !p.transformed) p.formeChange("cramorant")
                if (p.baseSpecies.name == "Rayquaza") p.canMegaEvo = null
                add("-start", p, "Dynamax", if (p.gigantamax) "Gmax" else "")
                if (p.baseSpecies.name == "Shedinja") return@on Unit
                val ratio = 1.5 + p.dynamaxLevel * 0.05
                p.maxhp = Math.floor(p.maxhp * ratio).toInt()
                p.hp = Math.floor(p.hp * ratio).toInt()
                add("-heal", p, p.getHealth, "[silent]")
                Unit
            }
            on("TryAddVolatile") { if ((relay as EffectLike).id == "flinch") null else Unit }
            on("BeforeSwitchOut") { pokemon.removeVolatile("dynamax"); Unit }
            on("SourceModifyDamage") {
                if (move.id == "behemothbash" || move.id == "behemothblade" || move.id == "dynamaxcannon") chainModify(2) else Unit
            }
            on("DragOut") { add("-block", pokemon, "Dynamax"); null }
            on("Residual") { state["turns"] = state.int("turns") + 1; Unit }
            on("End") {
                val p = pokemon
                add("-end", p, "Dynamax")
                if (p.baseSpecies.name == "Shedinja") return@on Unit
                p.hp = p.getUndynamaxedHP()
                p.maxhp = p.baseMaxhp
                add("-heal", p, p.getHealth, "[silent]")
                Unit
            }
        }
        condition("commanded") {
            on("Start") { boost(linkedMapOf("atk" to 2, "spa" to 2, "spe" to 2, "def" to 2, "spd" to 2), pokemon); Unit }
            on("DragOut") { false }
            on("TrapPokemon") { pokemon.trapped = true; Unit }
        }
        condition("commanding") {
            on("DragOut") { false }
            on("TrapPokemon") { pokemon.trapped = true; Unit }
            on("BeforeTurn") { battle.queue.cancelAction(pokemon); Unit }
        }
        condition("arceus") {
            on("Type") {
                val p = pokemon
                if (p.transformed || p.ability != "multitype") return@on relay
                listOf((p.getItem().data("onPlate") as? String) ?: "Normal")
            }
        }
        condition("silvally") {
            on("Type") {
                val p = pokemon
                if (p.transformed || p.ability != "rkssystem") return@on relay
                listOf((p.getItem().data("onMemory") as? String) ?: "Normal")
            }
        }
        condition("rolloutstorage") {
            on("BasePower") {
                val src = pokemon
                var bp = maxOf(1, move.basePower).toDouble()
                bp *= Math.pow(2.0, src.volatiles.getValue("rolloutstorage").int("contactHitCount").toDouble())
                if (src.volatiles["defensecurl"] != null) bp *= 2
                src.removeVolatile("rolloutstorage")
                Js.number(bp)
            }
        }
    }

    private fun HookRegistrar.weather(id: String, name: String, rock: String, boost: String, suppress: String) {
        condition(id) {
            callback("durationCallback") { if ((target as? Pokemon)?.hasItem(rock) == true) 8 else 5 }
            on("WeatherModifyDamage") {
                val defender = source as Pokemon
                if (defender.effectiveWeather() != id) return@on Unit
                if (move.type == boost) return@on chainModify(1.5)
                if (move.type == suppress) return@on chainModify(0.5)
                Unit
            }
            on("FieldStart") {
                val se = sourceEffect
                if (se?.effectType == "Ability") add("-weather", name, "[from] ability: " + se.name, "[of] $source")
                else add("-weather", name)
                Unit
            }
            on("FieldResidual") { add("-weather", name, "[upkeep]"); battle.eachEvent("Weather"); Unit }
            on("FieldEnd") { add("-weather", "none"); Unit }
        }
    }
}
