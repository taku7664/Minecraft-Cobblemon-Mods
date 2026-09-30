package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResultHolder

/**
 * Right-clicking a PokemonToItem item turns it back into its Pokemon, the way `/itemtopoke` does with the held item.
 * The Pokemon goes to the party, or the PC when the party is full, and exactly one item is used up.
 */
object PokemonItemRestore {
    private const val KEY = "message.${JbroPolicy.MOD_ID}.pokemon_item."

    fun register() {
        UseItemCallback.EVENT.register { player, level, hand ->
            val stack = player.getItemInHand(hand)
            val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.let(::pokemonData)
            // The client does not know the item's data is valid; it waits for the server's answer.
            if (data == null || player !is ServerPlayer) return@register InteractionResultHolder.pass(stack)
            val pokemon = try { Pokemon().loadFromNBT(level.registryAccess(), data) } catch (failure: RuntimeException) {
                JbroPolicy.LOGGER.warn("Could not read the Pokemon on {}'s item", player.gameProfile.name, failure)
                null
            }
            if (pokemon == null || !Cobblemon.storage.getParty(player).add(pokemon)) {
                player.sendSystemMessage(Component.translatable(KEY + "failed").withStyle(ChatFormatting.RED))
                return@register InteractionResultHolder.fail(stack)
            }
            stack.shrink(1)
            player.sendSystemMessage(Component.translatable(KEY + "restored", pokemon.getDisplayName(false)).withStyle(ChatFormatting.GREEN))
            InteractionResultHolder.success(stack)
        }
    }

    /** PokemonToItem 0.2.0 keeps the Pokemon under this key of the item's custom data. */
    internal fun pokemonData(customData: CompoundTag): CompoundTag? =
        if (customData.contains("PTI_NBT", Tag.TAG_COMPOUND.toInt())) customData.getCompound("PTI_NBT") else null
}
