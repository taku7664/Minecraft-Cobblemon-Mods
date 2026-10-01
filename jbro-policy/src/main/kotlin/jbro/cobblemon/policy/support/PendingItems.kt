package jbro.cobblemon.policy.support

import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData

/**
 * Items operators sent to players who were offline, handed over the next time each one joins. What does not fit in
 * the inventory drops at the player's feet.
 */
class PendingItems private constructor(private val waiting: MutableMap<UUID, MutableList<ItemStack>>) : SavedData() {
    fun add(player: UUID, stacks: List<ItemStack>) {
        waiting.getOrPut(player) { mutableListOf() }.addAll(stacks.map(ItemStack::copy))
        setDirty()
    }

    private fun take(player: UUID): List<ItemStack> = waiting.remove(player).orEmpty().also { if (it.isNotEmpty()) setDirty() }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("players", CompoundTag().apply {
            for ((player, stacks) in waiting) if (stacks.isNotEmpty()) {
                put(player.toString(), ListTag().apply { stacks.forEach { add(it.save(registries)) } })
            }
        })
    }

    companion object {
        private val factory = Factory({ PendingItems(mutableMapOf()) }, { tag, registries ->
            val players = tag.getCompound("players")
            PendingItems(players.allKeys.mapNotNull { key ->
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
                uuid to players.getList(key, Tag.TAG_COMPOUND.toInt())
                    .mapNotNull { ItemStack.parse(registries, it).orElse(null) }.toMutableList()
            }.toMap(mutableMapOf()))
        }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): PendingItems = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_pending_items")

        /** Puts [stacks] in [player]'s inventory, dropping what does not fit. */
        fun hand(player: ServerPlayer, stacks: List<ItemStack>) {
            for (stack in stacks) {
                val rest = stack.copy()
                if (!player.inventory.add(rest) || !rest.isEmpty) player.drop(rest, false)
            }
        }

        fun register() {
            ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
                val player = handler.player
                val stacks = get(server).take(player.uuid)
                if (stacks.isEmpty()) return@register
                hand(player, stacks)
                player.sendSystemMessage(Component.translatable("message.${JbroPolicy.MOD_ID}.pending_items", stacks.sumOf { it.count })
                    .withStyle(ChatFormatting.GREEN))
            }
        }
    }
}
