package jbro.cobblemon.ui.extended.ui.shared

import java.util.UUID

/** The native tile call does not pass its Pokémon UUID to drawBattleTile. */
object BattleHudContext {
    private val activePokemon = ThreadLocal<UUID?>()

    @JvmStatic
    fun begin(uuid: UUID?) { activePokemon.set(uuid) }

    @JvmStatic
    fun end() { activePokemon.remove() }

    val pokemonId: UUID? get() = activePokemon.get()
}
