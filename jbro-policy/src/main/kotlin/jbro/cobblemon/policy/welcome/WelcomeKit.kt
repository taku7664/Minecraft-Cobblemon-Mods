package jbro.cobblemon.policy.welcome

import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData

/** A player's very first join gets Poke Balls, Potions and a greeting. The Pokenav needs no item: it is /pokenav. */
object WelcomeKit {
    private val KIT = listOf("cobblemon:poke_ball" to 20, "cobblemon:potion" to 10)
    private const val KEY = "message.${JbroPolicy.MOD_ID}.welcome."

    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> grantIfFirstJoin(handler.player) }
    }

    private fun grantIfFirstJoin(player: ServerPlayer) {
        val granted = Granted.get(player)
        // Leaving increments this stat, so it tells players who were here before this mod from new ones.
        if (!isFirstJoin(player.uuid in granted, player.stats.getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)))) return
        granted.add(player.uuid)
        for ((id, count) in KIT) {
            val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null)
            if (item == null) {
                JbroPolicy.LOGGER.warn("Welcome kit item {} is missing; {} did not get it", id, player.gameProfile.name)
                continue
            }
            val stack = ItemStack(item, count)
            if (!player.inventory.add(stack)) player.drop(stack, false)
        }
        player.sendSystemMessage(Component.translatable(KEY + "greeting").withStyle(ChatFormatting.GOLD))
        player.sendSystemMessage(Component.translatable(KEY + "starter").withStyle(ChatFormatting.YELLOW))
        player.sendSystemMessage(Component.translatable(KEY + "pokenav").withStyle(ChatFormatting.YELLOW))
    }

    internal fun isFirstJoin(alreadyGranted: Boolean, leaveGameCount: Int): Boolean = !alreadyGranted && leaveGameCount == 0

    private class Granted(private val players: MutableSet<UUID>) : SavedData() {
        operator fun contains(player: UUID) = player in players

        fun add(player: UUID) {
            if (players.add(player)) setDirty()
        }

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
            put("players", net.minecraft.nbt.ListTag().apply { players.forEach { add(StringTag.valueOf(it.toString())) } })
        }

        companion object {
            private val factory = Factory({ Granted(mutableSetOf()) }, { tag, _ ->
                Granted(tag.getList("players", Tag.TAG_STRING.toInt()).mapNotNull { runCatching { UUID.fromString(it.asString) }.getOrNull() }.toMutableSet())
            }, DataFixTypes.LEVEL)
            fun get(player: ServerPlayer): Granted = player.server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_welcome_kits")
        }
    }
}
