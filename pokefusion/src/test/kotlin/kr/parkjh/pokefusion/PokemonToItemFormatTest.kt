package kr.parkjh.pokefusion

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokemonToItemFormatTest {
    @Test
    fun `recognizes the PokemonToItem 0_2_0 nbt key`() {
        val root = CompoundTag()
        assertFalse(PokemonToItemFormat.hasPokemonData(root))

        root.put("PTI_NBT", CompoundTag())
        assertTrue(PokemonToItemFormat.hasPokemonData(root))
    }

    @Test
    fun `finds only the three-line PokemonToItem 0_2_0 iv lore block`() {
        val actualFormat = listOf("Level: 50", "IVs:", "  HP:31  Atk:20  Def:19", "  SpAtk:22  SpDef:23  Spd:24", "EVs:")
        assertEquals(1..3, PokemonToItemFormat.findIvLoreBlock(actualFormat))
        assertEquals(null, PokemonToItemFormat.findIvLoreBlock(listOf("IVs:", "HP omitted", "SpAtk: 31")))
    }

    @Test
    fun `fabric metadata accepts the tested PokemonToItem version and newer`() {
        val metadata = requireNotNull(javaClass.getResource("/fabric.mod.json")).readText()

        assertTrue(metadata.contains("\"pokemontoitem\": \">=${PokemonToItemFormat.TESTED_VERSION}\""))
    }
}
