package kr.parkjh.pokefusion

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PokemonItemFormatTest {
    @Test fun `fusion preserves full Pokemon data through the shared item format`() {
        val pokemon = CompoundTag().apply {
            putUUID("UUID", UUID.randomUUID()); putString("Species", "cobblemon:pikachu")
            putFloat("ScaleModifier", 1.08f); putBoolean("Alpha", true)
        }
        val root = CompoundTag()
        assertFalse(PokemonItemFormat.hasPokemonData(root))
        PokemonItemFormat.putPokemonData(root, pokemon)
        assertTrue(PokemonItemFormat.hasPokemonData(root))
        assertEquals(pokemon, PokemonItemFormat.pokemonData(root))
        assertNull(PokemonItemFormat.pokemonData(CompoundTag().apply { put("PTI_NBT", pokemon) }))
    }
    @Test fun `fusion requires the shared tooltip and item provider instead of legacy PokemonToItem`() {
        val metadata = requireNotNull(javaClass.getResource("/fabric.mod.json")).readText()
        assertTrue(metadata.contains("\"cobblemon_to_item\": \">=0.1.0\""))
        assertFalse(metadata.contains("\"pokemontoitem\""))
    }
}
