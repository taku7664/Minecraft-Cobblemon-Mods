package kr.parkjh.pokefusion

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

object PokemonToItemFormat {
    const val TESTED_VERSION = "0.2.0"
    private const val POKEMON_NBT_KEY = "PTI_NBT"

    fun hasPokemonData(root: CompoundTag): Boolean =
        root.contains(POKEMON_NBT_KEY, Tag.TAG_COMPOUND.toInt())

    fun pokemonData(root: CompoundTag): CompoundTag? =
        if (hasPokemonData(root)) root.getCompound(POKEMON_NBT_KEY) else null

    fun putPokemonData(root: CompoundTag, pokemonData: CompoundTag) {
        root.put(POKEMON_NBT_KEY, pokemonData)
    }

    fun findIvLoreBlock(lines: List<String>): IntRange? {
        val header = lines.indexOfFirst { it.trim().startsWith("IVs:") }
        if (header < 0 || !lines.getOrNull(header + 1).orEmpty().contains("HP:") ||
            !lines.getOrNull(header + 2).orEmpty().contains("SpAtk:")) {
            return null
        }
        return header..(header + 2)
    }
}
