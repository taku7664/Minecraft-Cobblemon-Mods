package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopDelivery
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopDeliveryPlan
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopGrant
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack

internal class MinecraftBattlePointShopDelivery(
    private val server: MinecraftServer,
) : BattlePointShopDelivery {
    override fun prepare(playerId: UUID, grants: List<BattlePointShopGrant>): BattlePointShopDeliveryPlan? {
        val player = server.playerList.getPlayer(playerId) ?: return null
        val inventory = player.inventory
        val resolved = grants.map { grant ->
            val id = ResourceLocation.tryParse(grant.itemId) ?: return null
            val item = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: return null
            ItemStack(item, grant.count)
        }
        if (!canFit(inventory.items.map(ItemStack::copy), resolved)) return null
        val snapshot = inventory.items.map(ItemStack::copy)
        return object : BattlePointShopDeliveryPlan {
            private var committed = false

            override fun commit(): Boolean {
                if (committed) return true
                for (grant in resolved) {
                    val remaining = grant.copy()
                    if (!inventory.add(remaining) || !remaining.isEmpty) {
                        restore()
                        return false
                    }
                }
                inventory.setChanged()
                player.containerMenu.broadcastChanges()
                committed = true
                return true
            }

            override fun rollback() {
                if (committed) restore()
                committed = false
            }

            private fun restore() {
                snapshot.forEachIndexed { index, stack -> inventory.items[index] = stack.copy() }
                inventory.setChanged()
                player.containerMenu.broadcastChanges()
            }
        }
    }

    private fun canFit(initialSlots: List<ItemStack>, grants: List<ItemStack>): Boolean {
        val slots = initialSlots.map(ItemStack::copy).toMutableList()
        for (grant in grants) {
            var remaining = grant.count
            slots.forEachIndexed { index, existing ->
                if (remaining > 0 && !existing.isEmpty && ItemStack.isSameItemSameComponents(existing, grant)) {
                    val accepted = minOf(remaining, existing.maxStackSize - existing.count)
                    if (accepted > 0) {
                        slots[index] = existing.copyWithCount(existing.count + accepted)
                        remaining -= accepted
                    }
                }
            }
            slots.forEachIndexed { index, existing ->
                if (remaining > 0 && existing.isEmpty) {
                    val accepted = minOf(remaining, grant.maxStackSize)
                    slots[index] = grant.copyWithCount(accepted)
                    remaining -= accepted
                }
            }
            if (remaining > 0) return false
        }
        return true
    }
}
