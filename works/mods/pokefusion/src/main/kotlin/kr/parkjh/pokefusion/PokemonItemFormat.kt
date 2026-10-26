package kr.parkjh.pokefusion

import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.nbt.CompoundTag

/** Fusion uses the public Cobblemon To Item format; the base retains its other components. */
object PokemonItemFormat {
    private val stats = listOf("hp", "attack", "defence", "special_attack", "special_defence", "speed")

    /** Only the six base IVs may change; preserve even fields unknown to the current Pokemon codec. */
    fun withIvs(original: CompoundTag, values: IntArray): CompoundTag {
        require(values.size == stats.size && values.all { it in 0..31 }) { "invalid fusion IVs" }
        return original.copy().apply {
            val ivs = getCompound("IVs").copy()
            val base = ivs.getCompound("Base").copy()
            stats.forEachIndexed { index, stat -> base.putInt("cobblemon:$stat", values[index]) }
            ivs.put("Base", base)
            put("IVs", ivs)
        }
    }

    fun hasPokemonData(root: CompoundTag) = PokemonItemData.pokemonTag(root) != null
    fun pokemonData(root: CompoundTag) = PokemonItemData.pokemonTag(root)
    fun putPokemonData(root: CompoundTag, pokemon: CompoundTag) = PokemonItemData.putPokemon(root, pokemon)
}
