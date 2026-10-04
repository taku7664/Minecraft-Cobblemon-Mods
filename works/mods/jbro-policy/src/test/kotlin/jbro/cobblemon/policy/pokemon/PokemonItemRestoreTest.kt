package jbro.cobblemon.policy.pokemon

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PokemonItemRestoreTest {
    @Test
    fun `only a PokemonToItem compound counts as a Pokemon`() {
        val pokemon = CompoundTag().apply { putString("Species", "cobblemon:pikachu") }
        assertEquals(pokemon, PokemonItemRestore.pokemonData(CompoundTag().apply { put("PTI_NBT", pokemon) }))
        assertNull(PokemonItemRestore.pokemonData(CompoundTag()))
        assertNull(PokemonItemRestore.pokemonData(CompoundTag().apply { putString("PTI_NBT", "not a compound") }))
    }
}
