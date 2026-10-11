package jbro.cobblemon.partysidebar.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.ItemizePayload
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Method

/**
 * Itemizes one party Pokemon the way PokemonToItem's `/poketoitem` menu does: build the item with its
 * `PokemonUtility.pokemonToItem`, take the Pokemon out of the party, and give the item (dropped when the inventory is
 * full). Allowed only to players who may run `/poketoitem`, so that mod's permission setting still decides.
 * PokemonToItem is optional and reached by reflection; without it nothing happens.
 */
object PokemonItemize {
    private const val COMMAND = "poketoitem"
    private val converter: Pair<Any, Method>? by lazy {
        try {
            val utility = Class.forName("me.sreeraj.pokemontoitem.util.PokemonUtility")
            utility.getField("INSTANCE").get(null) to utility.getMethod("pokemonToItem", Pokemon::class.java)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun handle(player: ServerPlayer, payload: ItemizePayload) {
        if (player.isSpectator || player.containerMenu !== player.inventoryMenu) return
        val (utility, toItem) = converter ?: return
        val command = player.server.commands.dispatcher.root.getChild(COMMAND) ?: return
        if (!command.canUse(player.createCommandSourceStack())) return
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(payload.slot) ?: return
        if (pokemon.uuid != payload.pokemonId || pokemon.entity?.isBusy == true) return
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.itemize_in_battle"), true)
            return
        }
        val stack = try {
            toItem.invoke(utility, pokemon) as? ItemStack
        } catch (failure: ReflectiveOperationException) {
            CobblemonPartySidebar.LOGGER.warn("PokemonToItem could not turn {} into an item", pokemon.uuid, failure)
            null
        }
        if (stack == null || stack.isEmpty) return
        if (pokemon.entity != null) pokemon.recall()
        if (!party.remove(pokemon)) return
        if (!player.inventory.add(stack)) player.drop(stack, false)
        player.inventoryMenu.broadcastChanges()
    }
}
