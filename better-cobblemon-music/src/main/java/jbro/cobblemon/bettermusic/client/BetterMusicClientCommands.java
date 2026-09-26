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

    public static void register(BetterMusicConfigManager configManager) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                literal("bcm")
                    .then(literal("reload").executes(context -> reload(
                        configManager,
                        context.getSource()
                    )))
            )
        );
    }

    private static int reload(
        BetterMusicConfigManager configManager,
        FabricClientCommandSource source
    ) {
        var client = net.minecraft.client.Minecraft.getInstance();
        long before = configManager.lastReload().revision();
        client.reloadResourcePacks().whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                source.sendError(Component.translatable(
                    "better_cobblemon_music.command.reload.failure",
                    failure.getMessage()
                ));
                return;
            }
            var result = configManager.lastReload();
            if (result.outcome() == BetterMusicConfigManager.Outcome.APPLIED && result.revision() > before) {
                source.sendFeedback(Component.translatable(
                    "better_cobblemon_music.command.reload.success"
                ).withStyle(ChatFormatting.GREEN));
                return;
            }
            source.sendError(Component.translatable(
                "better_cobblemon_music.command.reload.failure",
                result.message()
            ));
        }, client);
        return 1;
    }
}
