package jbro.cobblemon.policy.api

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * `[안내] message` tips, one picked at random from the list every interval while anyone is online. The list starts
 * with the config's tips; other mods add their own with [add], or send one right away with [broadcast].
 */
object Tips {
    private val extra = CopyOnWriteArrayList<Component>()
    private var configured: List<Component> = emptyList()
    private var intervalTicks = 0
    private var last = -1

    /** Adds [message] to the tips picked from. */
    @JvmStatic
    fun add(message: Component) {
        extra.add(message)
    }

    /** Sends [message] as a tip now. */
    @JvmStatic
    fun broadcast(server: MinecraftServer, message: Component) {
        val tip = Component.empty()
            .append(Component.translatable("message.${JbroPolicy.MOD_ID}.tip").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(" ")
            .append(message)
        server.playerList.broadcastSystemMessage(tip, false)
    }

    internal fun register(intervalSeconds: Int, tips: List<String>) {
        configured = tips.filter { it.isNotBlank() }.map(Component::literal)
        intervalTicks = intervalSeconds * 20
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (intervalTicks <= 0 || server.tickCount % intervalTicks != 0 || server.playerList.playerCount == 0) return@register
            val all = configured + extra
            if (all.isEmpty()) return@register
            last = pick(all.size, last, ThreadLocalRandom.current().nextInt(all.size))
            broadcast(server, all[last])
        }
    }

    /** A random index in 0 until [count] from [roll], never [last] again while there is another to show. */
    internal fun pick(count: Int, last: Int, roll: Int): Int =
        if (count > 1 && roll == last) (roll + 1) % count else roll
}
