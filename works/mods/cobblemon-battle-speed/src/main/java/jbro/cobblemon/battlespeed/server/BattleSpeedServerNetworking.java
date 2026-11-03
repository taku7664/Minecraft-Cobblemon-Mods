package jbro.cobblemon.battlespeed.server;

import jbro.cobblemon.battlespeed.BattleSpeed;
import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.network.BattleSpeedSaveConfigPayload;
import jbro.cobblemon.battlespeed.network.BattleSpeedSetLevelPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/** Requests from the settings screen. The server re-checks every permission; the screen hiding a part is cosmetic. */
public final class BattleSpeedServerNetworking {
    private BattleSpeedServerNetworking() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BattleSpeedService.sync(handler.getPlayer()));
        ServerPlayNetworking.registerGlobalReceiver(
            BattleSpeedSetLevelPayload.TYPE,
            (payload, context) -> context.server().execute(() -> setLevel(context.player(), payload.level()))
        );
        ServerPlayNetworking.registerGlobalReceiver(
            BattleSpeedSaveConfigPayload.TYPE,
            (payload, context) -> context.server().execute(() -> saveConfig(context.player(), payload.configJson()))
        );
    }

    private static void setLevel(ServerPlayer player, int level) {
        var config = BattleSpeedService.config();
        if (!player.hasPermissions(config.permissions.change)) {
            player.sendSystemMessage(BattleSpeedText.noPermission());
            BattleSpeedService.sync(player);
            return;
        }
        switch (BattleSpeedService.changeLevel(player, level)) {
            case DISABLED -> player.sendSystemMessage(BattleSpeedText.disabled());
            case INVALID_LEVEL -> player.sendSystemMessage(BattleSpeedText.invalidLevel(config));
            case CHANGED -> {
                player.sendSystemMessage(BattleSpeedText.changed(config, level));
                if (BattleSpeedService.inIneligibleBattle(player)) {
                    player.sendSystemMessage(BattleSpeedText.notApplied());
                }
            }
        }
    }

    private static void saveConfig(ServerPlayer player, String json) {
        if (!player.hasPermissions(BattleSpeedService.config().permissions.reload)) {
            player.sendSystemMessage(BattleSpeedText.noPermission());
            BattleSpeedService.sync(player);
            return;
        }
        BattleSpeedConfig config;
        try {
            config = BattleSpeedConfig.fromJson(json);
            BattleSpeed.writeConfig(config);
        } catch (IOException | IllegalArgumentException exception) {
            BattleSpeed.LOGGER.warn("Could not save the battle speed config from {}", player.getScoreboardName(), exception);
            player.sendSystemMessage(BattleSpeedText.message(
                "save_failed", "배틀 배속 설정을 저장하지 못해 이전 설정을 유지합니다: %s",
                String.valueOf(exception.getMessage())
            ));
            BattleSpeedService.sync(player);
            return;
        }
        BattleSpeedService.applyConfig(config);
        BattleSpeed.LOGGER.info("{} saved the battle speed config from the settings screen", player.getScoreboardName());
        player.sendSystemMessage(BattleSpeedText.message("saved", "배틀 배속 설정을 저장하고 바로 적용했습니다."));
    }
}
