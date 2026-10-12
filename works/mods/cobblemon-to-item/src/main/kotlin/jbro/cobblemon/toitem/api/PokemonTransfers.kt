package jbro.cobblemon.toitem.api

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.toitem.CobblemonToItem
import jbro.cobblemon.toitem.config.ToItemConfig
import jbro.cobblemon.toitem.server.MoveTransaction
import jbro.cobblemon.toitem.server.TransferRules
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** Public server-thread API. All entrypoints use the same validation and storage operations. */
object PokemonTransfers {
    const val COMMAND = "poke2item"

    @JvmStatic fun allowed(player: ServerPlayer, inventoryOnly: Boolean = false): Boolean {
        if (inventoryOnly && player.containerMenu !== player.inventoryMenu) return false
        val battling = BattleRegistry.getBattleByParticipatingPlayer(player) != null
        if (battling) return reject(player, "in_battle")
        val permitted = player.hasPermissions(ToItemConfig.permissionLevel)
        if (!permitted) return reject(player, "permission")
        return TransferRules.actorAllowed(player.isAlive, player.isSpectator, battling, permitted)
    }

    /** GUI paths never drop. Only the explicit command opts into dropOverflow. */
    @JvmStatic fun itemize(player: ServerPlayer, slot: Int, expectedId: UUID,
                          targetMenuSlot: Int = -1, dropOverflow: Boolean = false): Boolean =
        convert(player, slot, expectedId, targetMenuSlot, dropOverflow, toCursor = false)

    /** Ordinary sidebar click: convert at pickup time, independently of inventory capacity. */
    @JvmStatic fun pickup(player: ServerPlayer, slot: Int, expectedId: UUID): Boolean =
        convert(player, slot, expectedId, -1, dropOverflow = false, toCursor = true)

    private fun convert(player: ServerPlayer, slot: Int, expectedId: UUID, targetMenuSlot: Int,
                        dropOverflow: Boolean, toCursor: Boolean): Boolean {
        if (!allowed(player, inventoryOnly = !dropOverflow)) return false
        if (!dropOverflow && !player.inventoryMenu.carried.isEmpty) return reject(player, "changed")
        if (targetMenuSlot < -1 || targetMenuSlot >= player.inventoryMenu.slots.size) return reject(player, "invalid_slot")
        val party = Cobblemon.storage.getParty(player)
        if (slot !in 0 until party.size()) return reject(player, "invalid_slot")
        val pokemon = party.get(slot) ?: return reject(player, "empty_slot")
        if (pokemon.uuid != expectedId) return reject(player, "changed")
        if (pokemon.entity?.isBusy == true) return reject(player, "busy")
        val preferred = player.inventoryMenu.slots.getOrNull(targetMenuSlot)?.takeIf {
            it.container === player.inventory && it.containerSlot in 0 until 36 && !it.hasItem()
        }?.containerSlot
        val destination = if (toCursor) null else preferred ?: (0 until 36).firstOrNull { player.inventory.getItem(it).isEmpty }
        if (!toCursor && destination == null && !dropOverflow) return reject(player, "inventory_full")
        return guarded(player) {
            val stack = PokemonItemData.create(pokemon, player.registryAccess())
            val serialized = PokemonItemData.pokemonTag(stack) ?: return@guarded reject(player, "invalid_item")
            val decoded = Pokemon.loadFromNBT(player.registryAccess(), serialized)
            check(decoded.uuid == pokemon.uuid) { "Pokemon item changed identity during serialization" }
            val dropped = if (!toCursor && destination == null) ItemEntity(player.level(), player.x, player.y + 0.5, player.z, stack.copy()).apply {
                setDefaultPickUpDelay()
            } else null
            val success = MoveTransaction.execute(
                remove = { party.get(slot)?.uuid == expectedId && party.remove(pokemon) },
                deliver = {
                    if (toCursor) {
                        if (!player.inventoryMenu.carried.isEmpty) false
                        else { player.inventoryMenu.carried = stack; true }
                    } else if (destination != null) {
                        if (!player.inventory.getItem(destination).isEmpty) false
                        else { player.inventory.setItem(destination, stack); true }
                    } else player.level().addFreshEntity(dropped!!) && !dropped.isRemoved
                },
                rollback = {
                    dropped?.discard()
                    if (toCursor && player.inventoryMenu.carried === stack) player.inventoryMenu.carried = ItemStack.EMPTY
                    if (destination != null && player.inventory.getItem(destination) === stack) {
                        player.inventory.setItem(destination, ItemStack.EMPTY)
                    }
                    if (party.get(slot) !== pokemon) {
                        check(party.get(slot) == null) { "Original party slot was occupied during rollback" }
                        party.set(PartyPosition(slot), pokemon)
                    }
                },
            )
            if (!success) reject(player, "failed") else true
        }
    }

    /** Empty target restores; an occupied target is exchanged onto the cursor like a vanilla slot. */
    @JvmStatic fun restoreCursor(player: ServerPlayer, requestedSlot: Int, expectedId: UUID, expectedTarget: UUID?): Boolean {
        if (!allowed(player, inventoryOnly = true)) return false
        val stack = player.inventoryMenu.carried
        val tag = PokemonItemData.pokemonTag(stack) ?: return reject(player, "invalid_item")
        if (!TransferRules.sourceMatches(stack.count, expectedId, tag.getUUID("UUID"))) return reject(player, "changed")
        val party = Cobblemon.storage.getParty(player)
        if (requestedSlot !in 0 until party.size()) return reject(player, "invalid_slot")
        val previous = party.get(requestedSlot)
        if (previous?.uuid != expectedTarget) return reject(player, "changed")
        val original = stack.copy()
        if (previous != null) {
            if (previous.entity?.isBusy == true) return reject(player, "busy")
            return guarded(player) {
                val incoming = Pokemon.loadFromNBT(player.registryAccess(), tag)
                if (incoming.uuid != expectedId) return@guarded reject(player, "invalid_item")
                val pc = Cobblemon.storage.getPC(player)
                if (party.get(incoming.uuid) != null || pc.get(incoming.uuid) != null) return@guarded reject(player, "duplicate")
                val replacement = PokemonItemData.create(previous, player.registryAccess())
                val replacementData = PokemonItemData.pokemonTag(replacement) ?: return@guarded reject(player, "invalid_item")
                check(Pokemon.loadFromNBT(player.registryAccess(), replacementData).uuid == previous.uuid)
                MoveTransaction.execute(
                    remove = { party.get(requestedSlot) === previous && party.remove(previous) },
                    deliver = {
                        party.set(PartyPosition(requestedSlot), incoming)
                        check(party.get(requestedSlot) === incoming)
                        player.inventoryMenu.carried = replacement
                        true
                    },
                    rollback = {
                        if (party.get(requestedSlot) === incoming) party.remove(incoming)
                        if (party.get(requestedSlot) !== previous) {
                            check(party.get(requestedSlot) == null) { "Exchange target changed during rollback" }
                            party.set(PartyPosition(requestedSlot), previous)
                        }
                        player.inventoryMenu.carried = original
                    },
                )
            }
        }
        return restore(player, stack, requestedSlot, allowPc = false,
            consume = { player.inventoryMenu.carried = ItemStack.EMPTY },
            restoreSource = { player.inventoryMenu.carried = original })
    }

    /** Quick-move is only accepted in the survival inventory, for inventory/hotbar slots. */
    @JvmStatic fun restoreInventory(player: ServerPlayer, menuSlot: Int, expectedId: UUID): Boolean {
        if (player.containerMenu !== player.inventoryMenu || !player.inventoryMenu.carried.isEmpty) return false
        val source = player.inventoryMenu.slots.getOrNull(menuSlot) ?: return false
        if (source.container !== player.inventory || source.containerSlot !in 0 until 36) return false
        val stack = source.item
        val tag = PokemonItemData.pokemonTag(stack) ?: return reject(player, "invalid_item")
        if (!TransferRules.sourceMatches(stack.count, expectedId, tag.getUUID("UUID"))) return reject(player, "changed")
        val original = stack.copy()
        return restore(player, stack, null, allowPc = false,
            consume = { source.set(ItemStack.EMPTY) }, restoreSource = { source.set(original) })
    }

    /** The held-item use route may overflow to the PC; failed storage never consumes the item. */
    @JvmStatic fun restoreHeld(player: ServerPlayer, stack: ItemStack): Boolean =
        restore(player, stack, null, allowPc = true,
            consume = { stack.shrink(1) }, restoreSource = { stack.count = 1 })

    private fun restore(player: ServerPlayer, stack: ItemStack, requestedSlot: Int?, allowPc: Boolean,
                        consume: () -> Unit, restoreSource: () -> Unit): Boolean {
        if (!allowed(player, inventoryOnly = !allowPc)) return false
        val tag = PokemonItemData.pokemonTag(stack) ?: return reject(player, "invalid_item")
        return guarded(player) {
            val pokemon = Pokemon.loadFromNBT(player.registryAccess(), tag)
            if (pokemon.uuid != tag.getUUID("UUID")) return@guarded reject(player, "invalid_item")
            val party = Cobblemon.storage.getParty(player)
            val pc = Cobblemon.storage.getPC(player)
            if (party.get(pokemon.uuid) != null || pc.get(pokemon.uuid) != null) return@guarded reject(player, "duplicate")
            val slot = TransferRules.restoreSlot(requestedSlot, (0 until party.size()).map { party.get(it) != null })
            if (slot == null && (!allowPc || requestedSlot != null)) return@guarded reject(player, "party_full")
            val pcPosition = if (slot == null) pc.getFirstAvailablePosition() ?: return@guarded reject(player, "pc_full") else null
            // Server-thread transaction: any insertion/consumption exception removes the new placement and
            // restores the exact source item. Destination availability was checked before entering this block.
            MoveTransaction.execute(
                remove = { consume(); true },
                deliver = {
                    if (slot != null) party.set(PartyPosition(slot), pokemon) else pc.set(pcPosition!!, pokemon)
                    (if (slot != null) party.get(pokemon.uuid) else pc.get(pokemon.uuid)) === pokemon
                },
                rollback = {
                    if (party.get(pokemon.uuid) === pokemon) party.remove(pokemon)
                    if (pc.get(pokemon.uuid) === pokemon) pc.remove(pokemon)
                    restoreSource()
                },
            )
        }
    }

    private inline fun guarded(player: ServerPlayer, action: () -> Boolean): Boolean = try {
        action()
    } catch (failure: Exception) {
        CobblemonToItem.LOGGER.error("Pokemon transfer failed for {}", player.uuid, failure)
        reject(player, "failed")
    } finally {
        player.inventory.setChanged()
        player.inventoryMenu.broadcastChanges()
        if (player.containerMenu !== player.inventoryMenu) player.containerMenu.broadcastChanges()
    }

    private fun reject(player: ServerPlayer, reason: String): Boolean {
        player.displayClientMessage(Component.translatable("cobblemon_to_item.message.$reason").withStyle(ChatFormatting.RED), true)
        return false
    }
}
