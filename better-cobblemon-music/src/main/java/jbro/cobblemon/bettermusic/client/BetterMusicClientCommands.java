package jbro.cobblemon.bettermusic.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class BetterMusicClientCommands {
    private BetterMusicClientCommands() {
    }

    public static void register(
        BetterMusicConfigManager configManager,
        GeneratedMusicPackController packController
    ) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                literal("bcm")
                    .then(literal("reload").executes(context -> reload(
                        configManager,
                        packController,
                        context.getSource()
                    )))
            )
        );
    }

    private static int reload(
        BetterMusicConfigManager configManager,
        GeneratedMusicPackController packController,
        FabricClientCommandSource source
    ) {
        var result = configManager.prepareReload();
        if (result.success()) {
            var snapshot = result.snapshot().orElseThrow();
            var client = net.minecraft.client.Minecraft.getInstance();
            packController.prepareAndReload(
                snapshot,
                () -> configManager.activate(snapshot)
            ).thenAcceptAsync(packFailure -> {
                if (packFailure.isPresent()) {
                    source.sendError(Component.translatable(
                        "better_cobblemon_music.command.reload.failure",
                        packFailure.orElseThrow()
                    ));
                    return;
                }
                source.sendFeedback(Component.translatable(
                    "better_cobblemon_music.command.reload.success"
                ).withStyle(ChatFormatting.GREEN));
            }, client);
            return 1;
        }
        source.sendError(Component.translatable(
            "better_cobblemon_music.command.reload.failure",
            result.message()
        ));
        return 0;
    }
}
