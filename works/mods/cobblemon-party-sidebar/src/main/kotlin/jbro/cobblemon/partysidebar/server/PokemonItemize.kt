package jbro.cobblemon.partysidebar.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.PokemonToItemFormat
import jbro.cobblemon.partysidebar.network.ItemizePayload
import jbro.cobblemon.partysidebar.network.RestorePayload
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Method

/**
 * Moves Pokemon between the party and PokemonToItem items, as that mod's commands do, but for one party slot.
 *
 * Itemizing builds the item as the `/poketoitem` menu does: `PokemonUtility.pokemonToItem` only draws the item (model
 * and lore), so the saved Pokemon is attached as custom data (`slot`, `PTI_NBT`) the way the menu attaches it. Only
 * then does the Pokemon leave the party; the item goes to the inventory slot it was dropped on, or wherever it fits
 * (dropped when the inventory is full).
 * Restoring reads the cursor item the way `/itemtopoke` does and puts the Pokemon in the chosen empty party slot.
 * Each is allowed only to players who may run the matching command, so PokemonToItem's permission settings decide.
 * PokemonToItem is optional and reached by reflection; without it nothing happens.
 */
object PokemonItemize {
    private const val PLAYER_INVENTORY_SLOTS = 36
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

    fun itemize(player: ServerPlayer, payload: ItemizePayload) {
        if (!allowed(player, PokemonToItemFormat.ITEMIZE_COMMAND)) return
        val (utility, toItem) = converter ?: return
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(payload.slot) ?: return
        if (pokemon.uuid != payload.pokemonId || pokemon.entity?.isBusy == true) return
        val stack = try {
            (toItem.invoke(utility, pokemon) as? ItemStack)?.also {
                it.set(DataComponents.CUSTOM_DATA, PokemonToItemFormat.customData(payload.slot, pokemon.saveToNBT(player.registryAccess())))
            }
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.warn("PokemonToItem could not turn {} into an item", pokemon.uuid, failure)
            null
        }
        // Never take a Pokemon out of the party without an item that can bring it back.
        if (stack == null || PokemonToItemFormat.pokemonTag(stack) == null) return
        if (pokemon.entity != null) pokemon.recall()
        if (!party.remove(pokemon)) return
        val menu = player.inventoryMenu
        val target = payload.targetSlot.takeIf { it in 0 until menu.slots.size }?.let { menu.getSlot(it) }
        if (target != null && target.container === player.inventory && target.containerSlot < PLAYER_INVENTORY_SLOTS &&
            !target.hasItem() && target.mayPlace(stack)) {
            target.set(stack)
        } else if (!player.inventory.add(stack)) {
            player.drop(stack, false)
        }
        menu.broadcastChanges()
    }

    fun restore(player: ServerPlayer, payload: RestorePayload) {
        if (!allowed(player, PokemonToItemFormat.RESTORE_COMMAND)) return
        val party = Cobblemon.storage.getParty(player)
        if (payload.slot !in 0 until party.size() || party.get(payload.slot) != null) return
        val menu = player.inventoryMenu
        val carried = menu.carried
        val tag = PokemonToItemFormat.pokemonTag(carried) ?: return
        val pokemon = try {
            Pokemon.loadFromNBT(player.registryAccess(), tag)
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.warn("Could not read the Pokemon in a PokemonToItem item", failure)
            return
        }
        if (party.any { it.uuid == pokemon.uuid }) return
        // The party has a free slot, so this adds to the party, not the PC; then the Pokemon moves to the chosen slot.
        if (!party.add(pokemon) || party.get(pokemon.uuid) == null) return
        party.move(pokemon, PartyPosition(payload.slot))
        carried.shrink(1)
        if (carried.isEmpty) menu.carried = ItemStack.EMPTY
        menu.broadcastChanges()
    }

    private fun allowed(player: ServerPlayer, command: String): Boolean {
        if (player.isSpectator || player.containerMenu !== player.inventoryMenu) return false
        val node = player.server.commands.dispatcher.root.getChild(command) ?: return false
        if (!node.canUse(player.createCommandSourceStack())) return false
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.itemize_in_battle"), true)
            return false
        }
        return true
    }
}
