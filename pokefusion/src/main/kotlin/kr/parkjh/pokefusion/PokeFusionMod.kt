package kr.parkjh.pokefusion

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

class PokeFusionMod : ModInitializer {
    override fun onInitialize() {
        PokeFusionPlayerStorage.initialize()
        val config = PokeFusionConfigStore(FabricLoader.getInstance().configDir).load()
        ServerLifecycleEvents.SERVER_STARTED.register {
            PokeFusionService.refreshEvolutionFamilies()
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, successful ->
            if (successful) PokeFusionService.refreshEvolutionFamilies()
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            PendingOutputService.recoverAfterJoin(handler.player)
        }
        // Fabric fires this event before vanilla onDisconnect saves the player.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (handler.player.containerMenu is PokeFusionMenu) handler.player.doCloseContainer()
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommand(dispatcher, config) }
    }

    private fun registerCommand(dispatcher: CommandDispatcher<CommandSourceStack>, config: PokeFusionConfig) {
        dispatcher.register(
            Commands.literal("pokefusion")
                .executes { context ->
                    if (!context.source.hasPermission(config.commandPermissionLevel)) {
                        context.source.sendFailure(net.minecraft.network.chat.Component.literal("이 명령어를 사용할 권한이 없습니다."))
                        0
                    } else {
                        val player = context.source.playerOrException
                        player.openMenu(PokeFusionMenuProvider(player))
                        1
                    }
                }
        )
    }
}
