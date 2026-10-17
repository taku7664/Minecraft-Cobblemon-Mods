package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity
import com.cobblemon.mod.common.util.raycast
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext

/**
 * `/pokenav` (`/포켓내비`) opens Cobblenav's Pokenav without the item, the way using the item does. Cobblenav also registers a
 * `pokenav` root for its own subcommands; Brigadier merges the two, so those keep working.
 */
object PokenavCommand {
    private const val OS = "com.metacontent.cobblenav.os.PokenavOS"
    private const val PACKET = "com.metacontent.cobblenav.networking.packet.client.OpenPokenavPacket"

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("cobblenav")) return
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            for (name in listOf("pokenav", "포켓내비")) dispatcher.register(Commands.literal(name).executes { open(it.source.playerOrException) })
        }
    }

    /** Cobblenav is optional, so it is reached by reflection; its classes are not remapped. */
    private fun open(player: ServerPlayer): Int = try {
        val osClass = Class.forName(OS)
        val bool = java.lang.Boolean.TYPE
        // The basic Pokenav item's OS: version "Lite", location on, contacts, map and fishing aid off.
        val os = osClass.getConstructor(String::class.java, bool, bool, bool, bool).newInstance("Lite", true, false, false, false)
        val packet = Class.forName(PACKET).getConstructor(osClass, BlockPos::class.java).newInstance(os, snackInSight(player))
        packet.javaClass.getMethod("sendToPlayer", ServerPlayer::class.java).invoke(packet, player)
        1
    } catch (failure: ReflectiveOperationException) {
        JbroPolicy.LOGGER.error("Could not open the Pokenav through Cobblenav", failure)
        player.sendSystemMessage(Component.translatable("message.${JbroPolicy.MOD_ID}.pokenav.failed").withStyle(ChatFormatting.RED))
        0
    }

    /**
     * The item shows a Poke Snack's spawns when the player looks at one while using it, so the command does the same:
     * the same ray (block reach, outlines, no fluids) as the item, and the snack's position when the ray ends on one.
     */
    private fun snackInSight(player: ServerPlayer): BlockPos? {
        val pos = player.raycast(player.blockInteractionRange().toFloat(), ClipContext.Fluid.NONE).blockPos
        return pos.takeIf { player.level().getBlockEntity(it) is PokeSnackBlockEntity }
    }
}
