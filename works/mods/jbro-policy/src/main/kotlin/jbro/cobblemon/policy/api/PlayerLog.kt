package jbro.cobblemon.policy.api

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/** A message to one player, in [color] (gray by default); other mods call [send] too. */
object PlayerLog {
    @JvmStatic
    @JvmOverloads
    fun send(player: ServerPlayer, message: Component, color: ChatFormatting = ChatFormatting.GRAY) {
        player.sendSystemMessage(message.copy().withStyle(color))
    }
}
