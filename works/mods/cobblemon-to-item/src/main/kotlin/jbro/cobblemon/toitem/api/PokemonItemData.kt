package jbro.cobblemon.toitem.api

import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/** Public, versioned format. Legacy PTI_NBT is deliberately left to the future policy migration. */
object PokemonItemData {
    const val KEY = "cobblemon_to_item"
    const val VERSION = 1

    @JvmStatic fun pokemonTag(root: CompoundTag): CompoundTag? {
        if (!root.contains(KEY, Tag.TAG_COMPOUND.toInt())) return null
        val data = root.getCompound(KEY)
        if (!data.contains("version", Tag.TAG_INT.toInt()) || data.getInt("version") != VERSION ||
            !data.contains("pokemon", Tag.TAG_COMPOUND.toInt())) return null
        val pokemon = data.getCompound("pokemon")
        if (!pokemon.hasUUID("UUID") || !pokemon.contains("Species", Tag.TAG_STRING.toInt()) ||
            pokemon.getString("Species").isBlank()) return null
        return pokemon.copy()
    }

    @JvmStatic fun pokemonTag(stack: ItemStack): CompoundTag? =
        if (stack.isEmpty || stack.count != 1 || stack.item !is PokemonItem) null
        else stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.let(::pokemonTag)

    @JvmStatic fun isPokemonItem(stack: ItemStack): Boolean = pokemonTag(stack) != null

    @JvmStatic fun putPokemon(root: CompoundTag, pokemon: CompoundTag) {
        root.put(KEY, CompoundTag().apply {
            putInt("version", VERSION)
            put("pokemon", pokemon.copy())
        })
    }

    @JvmStatic fun create(pokemon: Pokemon, registries: RegistryAccess): ItemStack = PokemonItem.from(pokemon).apply {
        set(DataComponents.CUSTOM_NAME, pokemon.getDisplayName())
        val root = CompoundTag()
        putPokemon(root, pokemon.saveToNBT(registries))
        set(DataComponents.CUSTOM_DATA, CustomData.of(root))
    }
}
