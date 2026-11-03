package jbro.cobblemon.battlespeed.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import jbro.cobblemon.battlespeed.BattleSpeed;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/** /battlespeed, /battlespeed <level>, /battlespeed reload. */
public final class BattleSpeedCommands {
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
            context.getSource().sendFailure(BattleSpeedText.disabled());
            return 0;
        }
        int level = BattleSpeedService.levelOf(player.getUUID());
        context.getSource().sendSuccess(() -> BattleSpeedText.current(config, level), false);
        return level;
    }

    private static int change(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        var config = BattleSpeedService.config();
        switch (BattleSpeedService.changeLevel(player, level)) {
            case DISABLED -> {
                context.getSource().sendFailure(BattleSpeedText.disabled());
                return 0;
            }
            case INVALID_LEVEL -> {
                context.getSource().sendFailure(BattleSpeedText.invalidLevel(config));
                return 0;
            }
            case CHANGED -> {
                context.getSource().sendSuccess(() -> BattleSpeedText.changed(config, level), false);
                if (BattleSpeedService.inIneligibleBattle(player)) {
                    context.getSource().sendSuccess(BattleSpeedText::notApplied, false);
                }
                return level;
            }
        }
        return 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            BattleSpeedService.applyConfig(BattleSpeed.readConfig());
        } catch (IOException | IllegalArgumentException exception) {
            BattleSpeed.LOGGER.warn("Could not reload the battle speed config", exception);
            context.getSource().sendFailure(BattleSpeedText.message(
                "reload_failed", "배틀 배속 설정을 불러오지 못해 이전 설정을 유지합니다: %s",
                String.valueOf(exception.getMessage())
            ));
            return 0;
        }
        context.getSource().sendSuccess(
            () -> BattleSpeedText.message("reloaded", "배틀 배속 설정을 다시 불러왔습니다."), true
        );
        return 1;
    }
}
