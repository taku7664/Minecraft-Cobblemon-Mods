package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge as Mod
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.LeagueEngine
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

object LeagueAdminCommands {
    fun register() = CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        dispatcher.register(literal("league-admin").requires { it.hasPermission(2) }
            .then(literal("inspect").then(argument("player", EntityArgument.player()).executes { context ->
                val target = EntityArgument.getPlayer(context, "player")
                val catalog = LeagueCatalogResources.current ?: return@executes 0
                val state = LeagueSavedData.get(target.server).read(catalog.id, target.uuid)
                context.source.sendSuccess({ Component.translatable("command.${Mod.MOD_ID}.status",
                    target.scoreboardName, LeagueEngine(catalog).badgeCount(state), LeagueEngine(catalog).cap(state), state.champion.toString()) }, false)
                1
            }))
            .then(literal("import-badges").then(argument("player", EntityArgument.player()).executes { context ->
                val target = EntityArgument.getPlayer(context, "player")
                val catalog = LeagueCatalogResources.current ?: return@executes 0
                val data = LeagueSavedData.get(target.server)
                val before = data.read(catalog.id, target.uuid)
                if (before.run != null) {
                    context.source.sendFailure(Component.translatable("message.${Mod.MOD_ID}.run_active"))
                    return@executes 0
                }
                val imported = catalog.gyms.filter { LeagueIntegrations.hasBadge(target, requireNotNull(catalog.challenges.getValue(it).badge)) }
                // Explicit operator migration only. It never awards BP or a Champion title.
                val after = before.copy(revision = before.revision + 1, cleared = before.cleared + imported,
                    unlockedCap = maxOf(before.unlockedCap, imported.maxOfOrNull { catalog.challenges.getValue(it).unlockCap } ?: 0))
                data.write(catalog.id, target.uuid, after)
                target.server.overworld().dataStorage.save()
                Mod.LOGGER.info("League badge import by {} for {}: {}", context.source.textName, target.uuid, imported)
                context.source.sendSuccess({ Component.translatable("command.${Mod.MOD_ID}.imported", imported.size, target.scoreboardName) }, true)
                1
            })))
    }
}
