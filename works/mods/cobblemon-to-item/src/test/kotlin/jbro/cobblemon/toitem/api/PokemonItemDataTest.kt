package jbro.cobblemon.toitem.api

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PokemonItemDataTest {
    private fun pokemon() = CompoundTag().apply {
        putUUID("UUID", UUID.randomUUID()); putString("Species", "cobblemon:pikachu")
        putFloat("ScaleModifier", 1.08f); putBoolean("Alpha", true)
        putInt("Experience", 1450); putInt("Friendship", 123)
        put("IVs", CompoundTag().apply { putInt("hp", 31) })
    }
    @Test fun `complete Pokemon data round trips without sharing mutable tags`() {
        val original = pokemon()
        val root = CompoundTag()
        PokemonItemData.putPokemon(root, original)
        val read = PokemonItemData.pokemonTag(root)!!
        assertEquals(original, read)
        read.putInt("Friendship", 0)
        original.putInt("Experience", 0)
        assertEquals(123, PokemonItemData.pokemonTag(root)!!.getInt("Friendship"))
        assertEquals(1450, PokemonItemData.pokemonTag(root)!!.getInt("Experience"))
    }
    @Test fun `legacy malformed missing identity and future versions are not restorable`() {
        assertNull(PokemonItemData.pokemonTag(CompoundTag().apply { put("PTI_NBT", pokemon()) }))
        assertNull(PokemonItemData.pokemonTag(CompoundTag().apply { putString(PokemonItemData.KEY, "broken") }))
        val root = CompoundTag()
        PokemonItemData.putPokemon(root, CompoundTag())
        assertNull(PokemonItemData.pokemonTag(root))
        PokemonItemData.putPokemon(root, pokemon())
        root.getCompound(PokemonItemData.KEY).putInt("version", 99)
        assertNull(PokemonItemData.pokemonTag(root))
    }
}
