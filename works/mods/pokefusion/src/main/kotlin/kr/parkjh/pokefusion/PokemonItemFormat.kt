package kr.parkjh.pokefusion

import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.nbt.CompoundTag

/** Fusion uses the public Cobblemon To Item format; the base retains its other components. */
object PokemonItemFormat {
    fun hasPokemonData(root: CompoundTag) = PokemonItemData.pokemonTag(root) != null
    fun pokemonData(root: CompoundTag) = PokemonItemData.pokemonTag(root)
    fun putPokemonData(root: CompoundTag, pokemon: CompoundTag) = PokemonItemData.putPokemon(root, pokemon)
}
