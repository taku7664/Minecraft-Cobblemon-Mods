package jbro.cobblemon.toitem.api.client

import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TooltipPokemonReaderTest {
    @Test fun `client adaptation leaves complete restoration data unchanged`() {
        val saved = CompoundTag().apply {
            putString(DataKeys.POKEMON_EVOLUTIONS, "server controller")
            putString(DataKeys.POKEMON_FORM_ID, "origin")
            putBoolean(DataKeys.POKEMON_SHINY, true)
            putInt(DataKeys.POKEMON_HEALTH, 13)
            put("IVs", CompoundTag().apply { putInt("hp", 31) })
        }
        val original = saved.copy()
        val client = TooltipPokemonReader.clientTag(saved)
        assertFalse(client.contains(DataKeys.POKEMON_EVOLUTIONS))
        assertEquals("origin", client.getString(DataKeys.POKEMON_FORM_ID))
        assertTrue(client.getBoolean(DataKeys.POKEMON_SHINY))
        assertEquals(13, client.getInt(DataKeys.POKEMON_HEALTH))
        client.getCompound("IVs").putInt("hp", 0)
        assertEquals(original, saved)
    }

    @Test fun `saved gender is read back for reapplying after client initialization`() {
        val female = CompoundTag().apply { putString(DataKeys.POKEMON_GENDER, "FEMALE") }
        assertEquals(Gender.FEMALE, TooltipPokemonReader.savedGender(female))
        assertNull(TooltipPokemonReader.savedGender(CompoundTag()))
        assertNull(TooltipPokemonReader.savedGender(CompoundTag().apply { putString(DataKeys.POKEMON_GENDER, "unknown") }))
    }

    @Test fun `omitted server defaults become valid client containers`() {
        val client = TooltipPokemonReader.clientTag(CompoundTag())
        assertInstanceOf(ListTag::class.java, client.get("Features"))
        assertInstanceOf(ListTag::class.java, client.get(DataKeys.POKEMON_MARKS))
        assertInstanceOf(ListTag::class.java, client.get(DataKeys.POKEMON_POTENTIAL_MARKS))
        assertInstanceOf(CompoundTag::class.java, client.get(DataKeys.POKEMON_RIDE_BOOSTS))
        assertInstanceOf(CompoundTag::class.java, client.get(DataKeys.POKEMON_INTERACTION_COOLDOWN))
        assertEquals(0, client.getInt(DataKeys.POKEMON_FULLNESS))
    }

    @Test fun `existing values including invalid ones are not silently defaulted`() {
        val saved = CompoundTag().apply {
            putInt(DataKeys.POKEMON_FULLNESS, 73)
            putString("Features", "invalid")
            put(DataKeys.POKEMON_RIDE_BOOSTS, CompoundTag().apply { putFloat("SPEED", 1.2f) })
        }
        val client = TooltipPokemonReader.clientTag(saved)
        assertEquals(73, client.getInt(DataKeys.POKEMON_FULLNESS))
        assertEquals("invalid", client.getString("Features"))
        assertEquals(saved.getCompound(DataKeys.POKEMON_RIDE_BOOSTS), client.getCompound(DataKeys.POKEMON_RIDE_BOOSTS))
    }
}
