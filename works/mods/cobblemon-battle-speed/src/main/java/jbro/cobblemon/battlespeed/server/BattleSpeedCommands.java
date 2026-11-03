package jbro.cobblemon.battlespeed.server;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import jbro.cobblemon.battlespeed.BattleSpeed;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/** /battlespeed, /battlespeed <level>, /battlespeed reload. */
public final class BattleSpeedCommands {
    private static final String KEY = "cobblemon_battle_speed.command.";

    private BattleSpeedCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("battlespeed")
            .requires(source -> source.hasPermission(BattleSpeedService.config().permissions.change))
            .executes(BattleSpeedCommands::show)
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(BattleSpeedService.config().permissions.reload))
                .executes(BattleSpeedCommands::reload))
            .then(Commands.argument("level", IntegerArgumentType.integer(1))
                .executes(context -> change(context, IntegerArgumentType.getInteger(context, "level")))));
    }

    private static int show(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        var config = BattleSpeedService.config();
        if (!config.enabled) {
            context.getSource().sendFailure(message("disabled", "배틀 배속이 꺼져 있습니다."));
            return 0;
        }
        int level = BattleSpeedService.levelOf(player.getUUID());
        context.getSource().sendSuccess(() -> message(
            "current", "현재 배틀 배속: %s단계 (%s배)", level, format(config.speedOf(level))
        ), false);
        return level;
    }

    private static int change(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        var config = BattleSpeedService.config();
        if (!config.enabled) {
            context.getSource().sendFailure(message("disabled", "배틀 배속이 꺼져 있습니다."));
            return 0;
        }
        if (level > config.levelCount()) {
            context.getSource().sendFailure(message(
                "invalid_level", "배속 단계는 1부터 %s까지입니다.", config.levelCount()
            ));
            return 0;
        }
        var store = BattleSpeedService.store();
        store.set(player.getUUID(), level);
        try {
            store.save();
        } catch (IOException exception) {
            BattleSpeed.LOGGER.warn("Could not save battle speed choices", exception);
        }
        context.getSource().sendSuccess(() -> message(
            "changed", "배틀 배속을 %s단계 (%s배)로 바꿨습니다.", level, format(config.speedOf(level))
        ), false);

        var battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        if (battle != null) {
            if (BattleSpeedService.eligible(battle)) {
                BattleSpeedService.broadcast(battle);
            } else {
                context.getSource().sendSuccess(() -> message(
                    "not_applied", "지금 하는 배틀에는 배속이 적용되지 않습니다."
                ), false);
            }
        }
        return level;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            BattleSpeed.reloadConfig();
            BattleSpeedService.broadcastAll();
        } catch (IOException | IllegalArgumentException exception) {
            BattleSpeed.LOGGER.warn("Could not reload the battle speed config", exception);
            context.getSource().sendFailure(message(
                "reload_failed", "배틀 배속 설정을 불러오지 못해 이전 설정을 유지합니다: %s",
                String.valueOf(exception.getMessage())
            ));
            return 0;
        }
        context.getSource().sendSuccess(() -> message("reloaded", "배틀 배속 설정을 다시 불러왔습니다."), true);
        return 1;
    }

    private static Component message(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(KEY + key, fallback, args);
    }

    private static String format(double speed) {
        return BattleSpeedPolicy.formatSpeed(speed);
    }
}
