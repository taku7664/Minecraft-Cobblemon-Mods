package kr.parkjh.pokefusion

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

object PokemonToItemFormat {
    const val TESTED_VERSION = "0.2.0"
    private const val POKEMON_NBT_KEY = "PTI_NBT"

    // Cobblemon 1.8 포켓몬 저장 코덱의 키(DataKeys.POKEMON_SCALE_MODIFIER, POKEMON_ALPHA).
    private const val SCALE_MODIFIER_KEY = "ScaleModifier"
    private const val ALPHA_KEY = "Alpha"

    fun hasPokemonData(root: CompoundTag): Boolean =
        root.contains(POKEMON_NBT_KEY, Tag.TAG_COMPOUND.toInt())

    fun pokemonData(root: CompoundTag): CompoundTag? =
        if (hasPokemonData(root)) root.getCompound(POKEMON_NBT_KEY) else null

    /** 저장된 크기 배율. 1.8 이전에 만든 아이템처럼 값이 없으면 null. */
    fun scaleModifier(pokemonData: CompoundTag): Float? =
        if (pokemonData.contains(SCALE_MODIFIER_KEY, Tag.TAG_ANY_NUMERIC.toInt())) pokemonData.getFloat(SCALE_MODIFIER_KEY) else null

    fun isAlpha(pokemonData: CompoundTag): Boolean = pokemonData.getBoolean(ALPHA_KEY)

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
