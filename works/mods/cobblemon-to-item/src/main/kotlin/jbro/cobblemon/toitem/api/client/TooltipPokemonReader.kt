package jbro.cobblemon.toitem.api.client

import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.DataKeys
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps

/** Read-only client view. The original, complete server payload is still used for restoration. */
internal object TooltipPokemonReader {
    fun read(registries: RegistryAccess, stored: CompoundTag): Pokemon {
        val view = Pokemon.CLIENT_CODEC.parse(
            registries.createSerializationContext(NbtOps.INSTANCE), clientTag(stored)
        ).orThrow
        // Server saves only forced aspects, while the client codec expects all visible aspects.
        // Preserve the saved form even when it was selected by a server-side aspect provider.
        val savedForm = view.species.forms.firstOrNull {
            it.formOnlyShowdownId() == stored.getString(DataKeys.POKEMON_FORM_ID)
        }
        if (savedForm != null && view.form != savedForm) {
            view.forcedAspects = view.forcedAspects + savedForm.aspects
            view.form = savedForm
            view.currentHealth = stored.getInt(DataKeys.POKEMON_HEALTH)
        }
        return view
    }

    internal fun clientTag(stored: CompoundTag): CompoundTag = stored.copy().apply {
        // Server evolution controllers have a different codec and are irrelevant to this tooltip.
        remove(DataKeys.POKEMON_EVOLUTIONS)
        // These values are optional in server saves but required by the public client codec.
        for (key in listOf("Features", DataKeys.POKEMON_MARKS, DataKeys.POKEMON_POTENTIAL_MARKS)) {
            if (!contains(key)) put(key, ListTag())
        }
        for (key in listOf(DataKeys.POKEMON_RIDE_BOOSTS, DataKeys.POKEMON_INTERACTION_COOLDOWN)) {
            if (!contains(key)) put(key, CompoundTag())
        }
        if (!contains(DataKeys.POKEMON_FULLNESS)) putInt(DataKeys.POKEMON_FULLNESS, 0)
    }
}
