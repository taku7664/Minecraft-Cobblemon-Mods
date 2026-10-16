package jbro.cobblemon.mcc.api.battle

import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * Which of a managed trainer's Pokemon may Dynamax and in what order, written on each team member as
 * `canDynamax=none|normal|gmax` and `dynamaxPriority=N`.
 *
 * `canDynamax`: `gmax` Dynamaxes as a Gigantamax (the Gigantamax factor is set), `normal` as a plain Dynamax (the factor
 * is cleared), `none` not at all; an unmarked member may, its factor as built.
 *
 * `dynamaxPriority`: who gets the team's one Dynamax. Only the living members that may Dynamax and come first (1 before
 * 2, any number before none) are offered it; when they all faint without it, the next ones are. So an ace marked 1 keeps
 * it while it lives, and its team still has it once the ace is gone (user decision, 2026-10-16: with a single allowed
 * member, an ace knocked out on a switch-in left its whole team without one). Members with the same priority, or none,
 * share the turn and the AI picks among them.
 *
 * The battle's own rules still decide whether Dynamax is allowed at all. Only the trainer's AI reads these: a player's
 * Pokemon carry no mark.
 */
object ManagedDynamax {
    enum class Permission(val id: String) {
        NONE("none"), NORMAL("normal"), GMAX("gmax");

        companion object {
            fun of(id: String): Permission = entries.firstOrNull { it.id == id.lowercase() }
                ?: throw IllegalArgumentException("canDynamax must be none, normal or gmax, not '$id'")
        }
    }

    /** The marks on one team member; nulls where it has none. */
    data class Mark(val permission: Permission? = null, val priority: Int? = null) {
        val isEmpty: Boolean get() = permission == null && priority == null
    }

    /** [line] without its Dynamax tokens, and the marks they gave. */
    fun split(line: String): Pair<String, Mark> {
        val tokens = line.trim().split(Regex("\\s+"))
        require(tokens.none { it.startsWith(OLD_TOKEN, ignoreCase = true) }) { "dynamax= is now canDynamax=: $line" }
        fun single(token: String) = tokens.filter { it.startsWith(token, ignoreCase = true) }
            .also { require(it.size <= 1) { "${token.dropLast(1)} is given more than once: $line" } }
            .singleOrNull()?.substring(token.length)
        val permission = single(PERMISSION_TOKEN)?.let(Permission::of)
        val priority = single(PRIORITY_TOKEN)?.let { value ->
            value.toIntOrNull()?.takeIf { it >= 1 } ?: throw IllegalArgumentException("dynamaxPriority must be 1 or more, not '$value'")
        }
        val rest = tokens.filterNot { it.startsWith(PERMISSION_TOKEN, ignoreCase = true) || it.startsWith(PRIORITY_TOKEN, ignoreCase = true) }
        return rest.joinToString(" ") to Mark(permission, priority)
    }

    /** Records [mark] on [pokemon], setting or clearing its Gigantamax factor to match a `canDynamax` given. */
    fun mark(pokemon: Pokemon, mark: Mark) {
        mark.permission?.let {
            pokemon.persistentData.putString(PERMISSION_KEY, it.id)
            pokemon.gmaxFactor = it == Permission.GMAX
        }
        mark.priority?.let { pokemon.persistentData.putInt(PRIORITY_KEY, it) }
    }

    /** Whether [pokemon] may Dynamax at all: unmarked, `normal` or `gmax`. Its turn is [permits] with the team. */
    fun permits(pokemon: Pokemon?): Boolean {
        val data = pokemon?.persistentData ?: return true
        if (!data.contains(PERMISSION_KEY)) return true
        return data.getString(PERMISSION_KEY) != Permission.NONE.id
    }

    /** [pokemon]'s `dynamaxPriority`, null without one. */
    fun priority(pokemon: Pokemon?): Int? = pokemon?.persistentData?.takeIf { it.contains(PRIORITY_KEY) }?.getInt(PRIORITY_KEY)

    /**
     * Whether [pokemon] may Dynamax now: it may at all, and no living teammate in [livingTeam] that may comes before it
     * by [priority].
     */
    fun permits(pokemon: Pokemon?, livingTeam: List<Pokemon>): Boolean =
        holdsTurn(Mark(permissionOf(pokemon), priority(pokemon)),
            livingTeam.filter { it !== pokemon }.map { Mark(permissionOf(it), priority(it)) })

    /** Whether a member marked [own] may Dynamax now, its living teammates marked [livingOthers]. */
    internal fun holdsTurn(own: Mark, livingOthers: List<Mark>): Boolean =
        own.permission != Permission.NONE && livingOthers.none { it.permission != Permission.NONE && rank(it) < rank(own) }

    private fun permissionOf(pokemon: Pokemon?): Permission? = if (permits(pokemon)) null else Permission.NONE

    private fun rank(mark: Mark): Int = mark.priority ?: Int.MAX_VALUE

    private const val OLD_TOKEN = "dynamax="
    private const val PERMISSION_TOKEN = "canDynamax="
    private const val PRIORITY_TOKEN = "dynamaxPriority="
    private const val PERMISSION_KEY = "more_cobblemon_contents:dynamax"
    private const val PRIORITY_KEY = "more_cobblemon_contents:dynamax_priority"
}
