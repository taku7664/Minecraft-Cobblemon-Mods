package jbro.cobblemon.mcc.internal.compat.cobblemon173

import java.util.UUID

/**
 * Stat stages read from the public Showdown log. Cobblemon's server-side battle never writes
 * `BattlePokemon.statChanges`, so without this every live board showed both sides at +0: a Draco
 * Meteor user still looked at full Special Attack after two casts, and a Nasty Plot sweeper looked
 * unboosted while it set up.
 */
internal class Cobblemon173PublicStatStages {
    private val stages = linkedMapOf<UUID, MutableMap<String, Int>>()
    private val activeBySlot = linkedMapOf<String, UUID>()

    fun reset() {
        stages.clear()
        activeBySlot.clear()
    }

    /** Stages of [pokemon], or null when the log has not touched it since it came in. */
    fun of(pokemon: UUID): Map<String, Int>? = stages[pokemon]?.filterValues { it != 0 }

    /**
     * [pokemon] resolves a message argument to the Pokemon it names, [argument] reads it raw.
     * [batonPass] is true for a switch that keeps the outgoing Pokemon's stages.
     */
    fun observe(id: String, pokemon: (Int) -> UUID?, argument: (Int) -> String?, batonPass: Boolean = false) {
        when (id) {
            "switch", "drag", "replace" -> {
                val incoming = pokemon(0) ?: return
                val slot = argument(0)?.substringBefore(':')?.trim() ?: return
                val outgoing = activeBySlot.put(slot, incoming)
                val carried = outgoing?.takeIf { batonPass && it != incoming }?.let { stages[it]?.toMutableMap() }
                outgoing?.takeIf { it != incoming }?.let(stages::remove)
                stages[incoming] = carried ?: linkedMapOf()
            }
            "faint" -> pokemon(0)?.let(stages::remove)
            "-boost", "-unboost" -> {
                val target = pokemon(0) ?: return
                val stat = stat(argument(1)) ?: return
                val amount = argument(2)?.trim()?.toIntOrNull() ?: return
                change(target, stat, if (id == "-boost") amount else -amount)
            }
            "-setboost" -> {
                val target = pokemon(0) ?: return
                val stat = stat(argument(1)) ?: return
                val amount = argument(2)?.trim()?.toIntOrNull() ?: return
                stagesOf(target)[stat] = amount.coerceIn(-6, 6)
            }
            "-clearboost" -> pokemon(0)?.let { stagesOf(it).clear() }
            "-clearallboost" -> stages.values.forEach(MutableMap<String, Int>::clear)
            "-clearnegativeboost" -> pokemon(0)?.let { target -> stagesOf(target).entries.removeIf { it.value < 0 } }
            "-clearpositiveboost" -> pokemon(0)?.let { target -> stagesOf(target).entries.removeIf { it.value > 0 } }
            "-invertboost" -> pokemon(0)?.let { target ->
                stagesOf(target).replaceAll { _, value -> -value }
            }
            // Psych Up: the first Pokemon takes the second's stages.
            "-copyboost" -> {
                val source = pokemon(0) ?: return
                val target = pokemon(1) ?: return
                stages[source] = stagesOf(target).toMutableMap()
            }
            // Guard Swap, Power Swap and Heart Swap: the listed stats, or all of them.
            "-swapboost" -> {
                val first = pokemon(0) ?: return
                val second = pokemon(1) ?: return
                val listed = argument(2)?.split(',')?.mapNotNull(::stat)?.takeIf { it.isNotEmpty() } ?: ALL_STATS
                val a = stagesOf(first)
                val b = stagesOf(second)
                listed.forEach { stat ->
                    val left = a[stat] ?: 0
                    a[stat] = b[stat] ?: 0
                    b[stat] = left
                }
            }
            "-transform" -> {
                val actor = pokemon(0) ?: return
                val target = pokemon(1) ?: return
                stages[actor] = stagesOf(target).toMutableMap()
            }
        }
    }

    private fun change(target: UUID, stat: String, amount: Int) {
        val current = stagesOf(target)
        current[stat] = ((current[stat] ?: 0) + amount).coerceIn(-6, 6)
    }

    private fun stagesOf(target: UUID): MutableMap<String, Int> = stages.getOrPut(target) { linkedMapOf() }

    private fun stat(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "atk" -> "attack"
        "def" -> "defence"
        "spa" -> "special_attack"
        "spd" -> "special_defence"
        "spe" -> "speed"
        "accuracy" -> "accuracy"
        "evasion" -> "evasion"
        else -> null
    }

    private companion object {
        val ALL_STATS = listOf("attack", "defence", "special_attack", "special_defence", "speed", "accuracy", "evasion")
    }
}
