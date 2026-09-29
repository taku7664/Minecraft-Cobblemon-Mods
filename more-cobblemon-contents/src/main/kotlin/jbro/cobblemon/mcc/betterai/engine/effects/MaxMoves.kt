package jbro.cobblemon.mcc.betterai.engine.effects

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.hooks.HookRegistrar
import jbro.cobblemon.mcc.betterai.engine.hooks.HookSet
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon

/**
 * The regular Max Moves. Gen 9 data marks them "Past", but Mega Showdown keeps Dynamax in gen 9, so the
 * engine needs their after-hit effects (the G-Max moves are ported with the other moves).
 */
object MaxMoves : HookSet() {
    override fun HookRegistrar.define() {
        foes("maxstrike", "spe")
        foes("maxwyrmwind", "atk")
        foes("maxphantasm", "def")
        foes("maxflutterby", "spa")
        foes("maxdarkness", "spd")
        allies("maxairstream", "spe")
        allies("maxooze", "spa")
        allies("maxquake", "spd")
        allies("maxknuckle", "atk")
        allies("maxsteelspike", "def")
        weather("maxflare", "sunnyday")
        weather("maxhailstorm", "hail")
        weather("maxgeyser", "raindance")
        weather("maxrockfall", "sandstorm")
        terrain("maxmindstorm", "psychicterrain")
        terrain("maxlightning", "electricterrain")
        terrain("maxovergrowth", "grassyterrain")
        terrain("maxstarfall", "mistyterrain")
        move("maxguard") {
            on("PrepareHit") { battle.queue.willAct() != null && Js.truthy(battle.runEvent("StallMove", pokemon)) }
            on("Hit") { pokemon.addVolatile("stall"); Unit }
            condition {
                on("Start") { add("-singleturn", target, "Max Guard"); Unit }
                on("TryHit") {
                    if (move.id in BYPASSES_MAX_GUARD) return@on Unit
                    val src = sourceMon!!
                    if (move.smartTarget == true) move.smartTarget = false else add("-activate", target, "move: Max Guard")
                    if (src.getVolatile("lockedmove") != null && src.volatiles["lockedmove"]?.duration == 2) src.volatiles.remove("lockedmove")
                    jbro.cobblemon.mcc.betterai.engine.sim.BattleActions.NOT_FAIL
                }
            }
        }
    }

    private val BYPASSES_MAX_GUARD = setOf("acupressure", "afteryou", "allyswitch", "aromatherapy", "aromaticmist", "coaching",
        "confide", "copycat", "curse", "decorate", "doomdesire", "feint", "futuresight", "gmaxoneblow", "gmaxrapidflow",
        "healbell", "holdhands", "howl", "junglehealing", "lifedew", "meanlook", "perishsong", "playnice", "powertrick",
        "roar", "roleplay", "tearfullook")

    private fun dynamaxed(source: Pokemon) = source.volatiles["dynamax"] != null

    private fun HookRegistrar.foes(id: String, stat: String) = move(id) {
        self {
            on("Hit") {
                if (!dynamaxed(pokemon)) return@on Unit
                for (foe in pokemon.foes()) boost(mapOf(stat to -1), foe)
                Unit
            }
        }
    }

    private fun HookRegistrar.allies(id: String, stat: String) = move(id) {
        self {
            on("Hit") {
                if (!dynamaxed(pokemon)) return@on Unit
                for (ally in pokemon.alliesAndSelf()) boost(mapOf(stat to 1), ally)
                Unit
            }
        }
    }

    private fun HookRegistrar.weather(id: String, weather: String) = move(id) {
        self {
            on("Hit") {
                if (!dynamaxed(pokemon)) return@on Unit
                field.setWeather(weather)
                Unit
            }
        }
    }

    private fun HookRegistrar.terrain(id: String, terrain: String) = move(id) {
        self {
            on("Hit") {
                if (!dynamaxed(pokemon)) return@on Unit
                field.setTerrain(terrain)
                Unit
            }
        }
    }
}
