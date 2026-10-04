package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.Cobblemon
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/** `/release <slot>` asks, and the clickable [Yes] confirms within two minutes. The last Pokemon always stays. */
object PartyRelease {
    private const val CONFIRMATION_MILLIS = 2 * 60 * 1000L
    private const val KEY = "message.${JbroPolicy.MOD_ID}.release."
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<UUID, Pending>()

    private data class Pending(val token: String, val slot: Int, val pokemon: UUID, val expiresAt: Long)

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("release")
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                    .executes { request(it.source.playerOrException, IntegerArgumentType.getInteger(it, "slot")) })
                .then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word())
                    .executes { confirm(it.source.playerOrException, StringArgumentType.getString(it, "token")) })))
        }
    }

    private fun request(player: ServerPlayer, slot: Int): Int {
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(slot - 1) ?: return fail(player, Component.translatable(KEY + "empty", slot))
        if (party.occupied() <= 1) return fail(player, Component.translatable(KEY + "last"))
        val token = java.lang.Long.toUnsignedString(random.nextLong(), 36)
        pending[player.uuid] = Pending(token, slot - 1, pokemon.uuid, System.currentTimeMillis() + CONFIRMATION_MILLIS)
        val yes = Component.translatable(KEY + "yes").withStyle { style ->
            style.withColor(ChatFormatting.GREEN).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/release confirm $token"))
        }
        player.sendSystemMessage(Component.translatable(KEY + "ask", slot, pokemon.getDisplayName(false)).append(" ").append(yes))
        return 1
    }

    private fun confirm(player: ServerPlayer, token: String): Int {
        val request = pending.remove(player.uuid)
        if (request == null || request.token != token || request.expiresAt < System.currentTimeMillis()) {
            return fail(player, Component.translatable(KEY + "expired"))
        }
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(request.slot)
        if (pokemon == null || pokemon.uuid != request.pokemon) return fail(player, Component.translatable(KEY + "changed"))
        if (party.occupied() <= 1) return fail(player, Component.translatable(KEY + "last"))
        val name = pokemon.getDisplayName(false)
        if (!party.remove(pokemon)) return fail(player, Component.translatable(KEY + "failed"))
        player.sendSystemMessage(Component.translatable(KEY + "done", name))
        return 1
    }

    private fun fail(player: ServerPlayer, message: Component): Int {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED))
        return 0
    }
}
