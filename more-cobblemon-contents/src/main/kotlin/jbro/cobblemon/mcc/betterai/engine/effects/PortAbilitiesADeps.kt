package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.effects.PortAbilitiesA.hasSecondaries
import jbro.cobblemon.mcc.betterai.engine.hooks.EffectHooks
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.ActiveMove
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/**
 * Move conditions the ported abilities start (Surges, Seed Sower, Hadron Engine, Cursed Body, Perish Body).
 * Only the `condition` blocks are here; the moves' own handlers belong with the move ports.
 */
object PortAbilitiesADeps : HookSet() {
    override fun HookRegistrar.define() {
        move("disable") {
            condition {
                on("Start") {
                    val p = pokemon
                    val active = battle.activeMove
                    if (battle.queue.willMove(p) != null || (p === battle.activePokemon && active != null && !active.isExternal)) {
                        state.duration = (state.duration ?: 0) - 1
                    }
                    val last = p.lastMove ?: return@on false
                    for (moveSlot in p.moveSlots) {
                        if (moveSlot.id == last.id && moveSlot.pp == 0) return@on false
                    }
                    if (sourceEffect?.effectType == "Ability") {
                        add("-start", p, "Disable", last.name, "[from] ability: Cursed Body", "[of] $source")
                    } else {
                        add("-start", p, "Disable", last.name)
                    }
                    state["move"] = last.id
                    Unit
                }
                on("End") { add("-end", target, "Disable"); Unit }
                on("BeforeMove") {
                    val m = move
                    if (!Js.truthy(m.isZ) && m.id == state["move"]) {
                        add("cant", target, "Disable", m)
                        return@on false
                    }
                    Unit
                }
                on("DisableMove") {
                    val p = pokemon
                    for (moveSlot in p.moveSlots) {
                        if (moveSlot.id == state["move"]) p.disableMove(moveSlot.id)
                    }
                    Unit
                }
            }
        }
        move("psychicterrain") {
            condition {
                terrainBasics(this, "Psychic Terrain")
                on("TryHit") {
                    val t = pokemon
                    val m = effect as? ActiveMove
                    if (m != null && (m.priority <= 0.1 || m.target == "self")) return@on Unit
                    if (t.isSemiInvulnerable() || t.isAlly(sourceMon)) return@on Unit
                    if (t.isGrounded() != true) {
                        val baseMove = dex.moveOrPlaceholder(m?.id ?: "")
                        if (baseMove.priority > 0) hint("Psychic Terrain doesn't affect Pokémon immune to Ground.")
                        return@on Unit
                    }
                    add("-activate", t, "move: Psychic Terrain")
                    null
                }
                on("BasePower") {
                    val attacker = pokemon
                    if (move.type == "Psychic" && attacker.isGrounded() == true && !attacker.isSemiInvulnerable()) {
                        chainModify(intArrayOf(5325, 4096))
                    } else Unit
                }
            }
        }
        move("grassyterrain") {
            condition {
                terrainBasics(this, "Grassy Terrain")
                on("BasePower") {
                    val attacker = pokemon
                    val defender = sourceMon
                    val m = move
                    if (m.id in listOf("earthquake", "bulldoze", "magnitude") && defender?.isGrounded() == true &&
                        !defender.isSemiInvulnerable()) {
                        return@on chainModify(0.5)
                    }
                    if (m.type == "Grass" && attacker.isGrounded() == true) chainModify(intArrayOf(5325, 4096)) else Unit
                }
                on("Residual") {
                    val p = pokemon
                    if (p.isGrounded() == true && !p.isSemiInvulnerable()) battle.heal(p.baseMaxhp / 16.0, p, p)
                    Unit
                }
            }
        }
        move("electricterrain") {
            condition {
                terrainBasics(this, "Electric Terrain")
                on("SetStatus") {
                    val t = pokemon
                    if ((relay as EffectLike).id == "slp" && t.isGrounded() == true && !t.isSemiInvulnerable()) {
                        val e = sourceEffect
                        if (e?.id == "yawn" || (e?.effectType == "Move" && !hasSecondaries(e))) add("-activate", t, "move: Electric Terrain")
                        return@on false
                    }
                    Unit
                }
                on("TryAddVolatile") {
                    val t = pokemon
                    if (t.isGrounded() != true || t.isSemiInvulnerable()) return@on Unit
                    if ((relay as EffectLike).id == "yawn") {
                        add("-activate", t, "move: Electric Terrain")
                        return@on null
                    }
                    Unit
                }
                on("BasePower") {
                    val attacker = pokemon
                    if (move.type == "Electric" && attacker.isGrounded() == true && !attacker.isSemiInvulnerable()) {
                        chainModify(intArrayOf(5325, 4096))
                    } else Unit
                }
            }
        }
        move("perishsong") {
            condition {
                on("End") {
                    val t = pokemon
                    add("-start", t, "perish0")
                    t.faint()
                    Unit
                }
                on("Residual") {
                    val p = pokemon
                    val duration = p.volatiles["perishsong"]?.duration
                    add("-start", p, "perish" + Js.str(duration))
                    Unit
                }
            }
        }
    }

    /** The terrains' shared `durationCallback`, `onFieldStart` and `onFieldEnd`. */
    private fun terrainBasics(hooks: EffectHooks, name: String) {
        hooks.callback("durationCallback") { if ((target as? Pokemon)?.hasItem("terrainextender") == true) 8 else 5 }
        hooks.on("FieldStart") {
            val e = sourceEffect
            if (e?.effectType == "Ability") add("-fieldstart", "move: $name", "[from] ability: " + e.name, "[of] $source")
            else add("-fieldstart", "move: $name")
            Unit
        }
        hooks.on("FieldEnd") { add("-fieldend", "move: $name"); Unit }
    }
}
