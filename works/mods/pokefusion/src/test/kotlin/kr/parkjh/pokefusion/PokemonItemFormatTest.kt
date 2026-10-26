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

    @Test fun `IV updates preserve alpha scale hypertraining future data and do not alias the source`() {
        val source = CompoundTag().apply {
            putUUID("UUID", UUID.randomUUID()); putString("Species", "cobblemon:cinderace")
            putBoolean("Alpha", true); putFloat("ScaleModifier", 1.08f)
            put("UnknownFutureState", CompoundTag().apply { putByteArray("payload", byteArrayOf(0, -1, 127)) })
            put("IVs", CompoundTag().apply {
                putString("future_iv_field", "keep")
                put("HyperTrained", CompoundTag().apply { putInt("cobblemon:attack", 31) })
                put("Base", CompoundTag().apply { putInt("future:stat", 123) })
            })
        }
        val before = source.copy()
        val result = PokemonItemFormat.withIvs(source, intArrayOf(0, 1, 15, 16, 30, 31))
        assertEquals(before, source)
        assertTrue(result.getBoolean("Alpha"))
        assertEquals(source.getCompound("UnknownFutureState"), result.getCompound("UnknownFutureState"))
        assertEquals(source.getCompound("IVs").getCompound("HyperTrained"), result.getCompound("IVs").getCompound("HyperTrained"))
        assertEquals("keep", result.getCompound("IVs").getString("future_iv_field"))
        assertEquals(123, result.getCompound("IVs").getCompound("Base").getInt("future:stat"))
        result.getCompound("UnknownFutureState").putString("changed", "result only")
        assertEquals(before, source)
        for (invalid in listOf(intArrayOf(), IntArray(5), IntArray(7), intArrayOf(-1, 0, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 32))) {
            assertThrows(IllegalArgumentException::class.java) { PokemonItemFormat.withIvs(source, invalid) }
            assertEquals(before, source)
        }
    }
}
