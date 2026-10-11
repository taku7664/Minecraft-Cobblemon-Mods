package jbro.cobblemon.partysidebar

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * PokemonToItem's item format, as its `/itemtopoke` reads it: the saved Pokemon sits under `PTI_NBT` in the item's
 * custom data. Its commands are `/poketoitem` and `/itemtopoke`; the server only sends a player the ones they may use.
 */
object PokemonToItemFormat {
    const val ITEMIZE_COMMAND = "poketoitem"
    const val RESTORE_COMMAND = "itemtopoke"
    private const val KEY = "PTI_NBT"

    /** The custom data the `/poketoitem` menu puts on its items: the party slot it came from and the saved Pokemon. */
    fun customData(slot: Int, pokemon: CompoundTag): CustomData = CustomData.of(CompoundTag().apply {
        putInt("slot", slot)
        put(KEY, pokemon)
    })

    fun isPokemonItem(stack: ItemStack): Boolean = stack.get(DataComponents.CUSTOM_DATA)?.contains(KEY) == true

    fun pokemonTag(stack: ItemStack): CompoundTag? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        if (!data.contains(KEY)) return null
        return data.copyTag().getCompound(KEY)
    }
}
