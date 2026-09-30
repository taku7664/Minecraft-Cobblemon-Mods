package jbro.cobblemon.mcc.internal.command

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.context.CommandContext
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** Argument helpers the operator commands of every content share. */
object MccAdminArguments {
    /**
     * The single player a [GameProfileArgument] named [name] resolves to, online or not, or null after telling the
     * source it named none or several.
     */
    fun profile(command: CommandContext<CommandSourceStack>, name: String = "player"): GameProfile? {
        val profiles = GameProfileArgument.getGameProfiles(command, name)
        if (profiles.size != 1) {
            command.source.sendFailure(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.admin.one_player"))
            return null
        }
        return profiles.single()
    }

    /** [playerId]'s name as the server knows it, or the ID when it has never seen them. */
    fun name(server: MinecraftServer, playerId: UUID): String =
        server.playerList.getPlayer(playerId)?.name?.string
            ?: server.profileCache?.get(playerId)?.map { it.name }?.orElse(null)
            ?: playerId.toString()
}
