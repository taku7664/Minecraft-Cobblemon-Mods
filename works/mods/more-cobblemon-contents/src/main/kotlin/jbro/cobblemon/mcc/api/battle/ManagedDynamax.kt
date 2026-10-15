package jbro.cobblemon.mcc.api.battle

import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * Which of a managed trainer's Pokemon may Dynamax, written on each team member as `dynamax=none|normal|gmax`.
 *
 * `gmax` Dynamaxes as a Gigantamax (the Gigantamax factor is set), `normal` as a plain Dynamax (the factor is cleared),
 * `none` not at all. A team that marks any member leaves its unmarked members at `none`; a team that marks none keeps
 * every member free to, as before. The battle's own rules still decide whether Dynamax is allowed at all. Only the
 * trainer's AI reads it: a player's Pokemon carry no mark.
 */
object ManagedDynamax {
    enum class Permission(val id: String) {
        NONE("none"), NORMAL("normal"), GMAX("gmax");

        companion object {
            fun of(id: String): Permission = entries.firstOrNull { it.id == id.lowercase() }
                ?: throw IllegalArgumentException("dynamax must be none, normal or gmax, not '$id'")
        }
    }

    /** [line] without its `dynamax=` token, and the permission the token gave (null without one). */
    fun split(line: String): Pair<String, Permission?> {
        val tokens = line.trim().split(Regex("\\s+"))
        val marks = tokens.filter { it.startsWith(TOKEN, ignoreCase = true) }
        require(marks.size <= 1) { "dynamax is given more than once: $line" }
        val permission = marks.singleOrNull()?.let { Permission.of(it.substring(TOKEN.length)) }
        return tokens.filterNot { it.startsWith(TOKEN, ignoreCase = true) }.joinToString(" ") to permission
    }

    /** Records [permission] on [pokemon], setting or clearing its Gigantamax factor to match. */
    fun mark(pokemon: Pokemon, permission: Permission) {
        pokemon.persistentData.putString(KEY, permission.id)
        if (permission == Permission.GMAX) pokemon.gmaxFactor = true
        else pokemon.gmaxFactor = false
    }

    /** Whether [pokemon] may Dynamax: unmarked, `normal` or `gmax`. */
    fun permits(pokemon: Pokemon?): Boolean {
        val data = pokemon?.persistentData ?: return true
        if (!data.contains(KEY)) return true
        return data.getString(KEY) != Permission.NONE.id
    }

    private const val TOKEN = "dynamax="
    private const val KEY = "more_cobblemon_contents:dynamax"
}
