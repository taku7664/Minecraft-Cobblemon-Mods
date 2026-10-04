package jbro.cobblemon.policy.pokemon

import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/pokenav` opens Cobblenav's Pokenav without the item, the way using the item does. Cobblenav also registers a
 * `pokenav` root for its own subcommands; Brigadier merges the two, so those keep working.
 */
object PokenavCommand {
    private const val OS = "com.metacontent.cobblenav.os.PokenavOS"
    private const val PACKET = "com.metacontent.cobblenav.networking.packet.client.OpenPokenavPacket"

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("cobblenav")) return
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("pokenav").executes { open(it.source.playerOrException) })
        }
    }

    /** Cobblenav is optional, so it is reached by reflection; its classes are not remapped. */
    private fun open(player: ServerPlayer): Int = try {
        val osClass = Class.forName(OS)
        val bool = java.lang.Boolean.TYPE
        // The basic Pokenav item's OS: version "Lite", location on, contacts, map and fishing aid off.
        val os = osClass.getConstructor(String::class.java, bool, bool, bool, bool).newInstance("Lite", true, false, false, false)
        val packet = Class.forName(PACKET).getConstructor(osClass, BlockPos::class.java).newInstance(os, null)
        packet.javaClass.getMethod("sendToPlayer", ServerPlayer::class.java).invoke(packet, player)
        1
    } catch (failure: ReflectiveOperationException) {
        JbroPolicy.LOGGER.error("Could not open the Pokenav through Cobblenav", failure)
        player.sendSystemMessage(Component.translatable("message.${JbroPolicy.MOD_ID}.pokenav.failed").withStyle(ChatFormatting.RED))
        0
    }
}
