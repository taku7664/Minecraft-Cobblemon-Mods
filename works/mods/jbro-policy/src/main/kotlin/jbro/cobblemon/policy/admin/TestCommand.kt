package jbro.cobblemon.policy.admin

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/test` gathers operator shortcuts for trying things out on your own party.
 *
 * `/test level set <1-6|all> <level|auto>` sets party levels; `auto` uses the level cap you have unlocked in the
 * League, so a test party matches what a player at that point could bring.
 */
object TestCommand {
    private const val KEY = "message.${JbroPolicy.MOD_ID}.test."
    private val leagueLoaded by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("test").requires { it.hasPermission(2) }
                .then(Commands.literal("level").then(Commands.literal("set")
                    .then(levels(Commands.argument("slot", IntegerArgumentType.integer(1, 6))) { IntegerArgumentType.getInteger(it, "slot") })
                    .then(levels(Commands.literal("all")) { null }))))
        }
    }

    private fun <T : ArgumentBuilder<CommandSourceStack, T>> levels(
        node: T, slot: (CommandContext<CommandSourceStack>) -> Int?,
    ): T = node
        .then(Commands.argument("level", IntegerArgumentType.integer(1, Cobblemon.config.maxPokemonLevel))
            .executes { setLevel(it.source.playerOrException, slot(it), IntegerArgumentType.getInteger(it, "level")) })
        .then(Commands.literal("auto").executes {
            val player = it.source.playerOrException
            val cap = levelCap(player) ?: return@executes fail(player, Component.translatable(KEY + "no_cap"))
            setLevel(player, slot(it), cap)
        })

    /** Sets [slot] (1-6), or every Pokemon in the party when it is null. */
    private fun setLevel(player: ServerPlayer, slot: Int?, level: Int): Int {
        val party = Cobblemon.storage.getParty(player)
        val targets: List<Pokemon> = if (slot == null) party.toList() else listOfNotNull(party.get(slot - 1))
        if (targets.isEmpty()) {
            return fail(player, if (slot == null) Component.translatable(KEY + "party_empty") else Component.translatable(KEY + "slot_empty", slot))
        }
        targets.forEach { it.level = level }
        player.sendSystemMessage(if (slot == null) Component.translatable(KEY + "level_all", targets.size, level)
            else Component.translatable(KEY + "level_one", slot, targets.single().getDisplayName(false), level))
        return targets.size
    }

    private fun levelCap(player: ServerPlayer): Int? {
        if (!leagueLoaded) return null
        return try { LeagueRanks.levelCap(player.server, player.uuid) } catch (failure: RuntimeException) {
            JbroPolicy.LOGGER.warn("Could not read the League level cap for {}", player.gameProfile.name, failure)
            null
        }
    }

    private fun fail(player: ServerPlayer, message: Component): Int {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED))
        return 0
    }
}
