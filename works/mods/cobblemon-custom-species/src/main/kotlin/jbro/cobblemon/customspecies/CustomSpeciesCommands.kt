package jbro.cobblemon.customspecies

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object CustomSpeciesCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("customspecies")
                    .requires { it.hasPermission(2) }
                    .then(
                        Commands.literal("status").executes { context ->
                            val status = CustomSpeciesReloadController.status
                            val text = if (status.successful) {
                                "Cobblemon Custom Species: ${status.message} (${CustomSpeciesConfigFile.path})"
                            } else {
                                "Cobblemon Custom Species: rejected - ${status.message} (${CustomSpeciesConfigFile.path})"
                            }
                            context.source.sendSuccess({ Component.literal(text) }, false)
                            if (status.successful) 1 else 0
                        }
                    )
            )
        }
    }
}
