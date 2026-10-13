package jbro.cobblemon.mcc.internal.compat.cobblemon173

/**
 * Lets a managed opponent stand above Cobblemon's `maxPokemonLevel` without raising it for anyone else.
 *
 * Cobblemon clamps a level to that setting wherever it sets one: reading `level=` from properties, `Pokemon.setLevel`,
 * and the save codec that [com.cobblemon.mod.common.battles.pokemon.BattlePokemon.Companion.safeCopyOf] copies through
 * (which fails the copy outright). While [during] runs, `CobblemonConfigMixin` reads the setting as at least the given
 * limit, on this thread only. Players' and wild Pokemon are built outside it and keep the server's maximum.
 */
object Cobblemon173OpponentLevelScope {
    private val limit = ThreadLocal<Int?>()

    /** The raised maximum while [during] runs on this thread, or null. */
    @JvmStatic
    fun limit(): Int? = limit.get()

    @JvmStatic
    fun <T> during(max: Int, block: () -> T): T {
        val previous = limit.get()
        limit.set(max)
        try {
            return block()
        } finally {
            if (previous == null) limit.remove() else limit.set(previous)
        }
    }
}
