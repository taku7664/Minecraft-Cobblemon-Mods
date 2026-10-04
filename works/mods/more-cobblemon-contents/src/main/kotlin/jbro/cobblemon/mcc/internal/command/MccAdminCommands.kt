package jbro.cobblemon.mcc.internal.command

import com.cobblemon.mod.common.battles.BattleRegistry
import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleForfeit
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import jbro.cobblemon.mcc.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * The operator commands shared by every content: `/mcc status`, `/mcc records reset` and `/mcc battle`. Contents
 * take part through [MccAdminSources].
 */
internal object MccAdminCommands {
    private const val KEY = "command.${MoreCobblemonContents.MOD_ID}.admin"

    fun register() {
        MccCommandContributors.register { status() }
        MccCommandContributors.register { records() }
        MccCommandContributors.register { battle() }
    }

    fun status(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("status")
        .requires(BattleProgressCommands::isAdmin)
        .executes { command ->
            val source = command.source
            val server = source.server
            line(source, Component.translatable("$KEY.status.header"))
            line(source, Component.translatable("$KEY.status.storage", Component.translatable("$KEY.source.bp"), available(BattlePointService.isAvailable(server))))
            line(source, Component.translatable("$KEY.status.storage", Component.translatable("$KEY.source.records"), available(BattleRecordService.isAvailable(server))))
            val shop = BattlePointShopCatalogResources.store.snapshot()
            line(source, if (shop == null) Component.translatable("$KEY.status.shop_missing")
                else Component.translatable("$KEY.status.shop", shop.catalogId, shop.entries().size))
            line(source, Component.translatable("$KEY.status.battles", Cobblemon173BattleRuleHooks.registeredBattleIds().size))
            MccAdminSources.all().forEach { admin ->
                val lines = safely(source, admin) { admin.status(server) } ?: return@forEach
                lines.forEach { line(source, Component.empty().append("[").append(admin.label).append("] ").append(it)) }
            }
            1
        }

    fun records(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("records")
        .requires(BattleProgressCommands::isAdmin)
        .then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile())
            .executes { resetRecords(it, null, null) }
            .then(Commands.argument("content", ResourceLocationArgument.id())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(CONTENTS, builder) }
                .executes { resetRecords(it, ResourceLocationArgument.getId(it, "content").toString(), null) }
                .then(Commands.argument("format", StringArgumentType.word()).executes {
                    resetRecords(it, ResourceLocationArgument.getId(it, "content").toString(), StringArgumentType.getString(it, "format"))
                }))))

    fun battle(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("battle")
        .requires(BattleProgressCommands::isAdmin)
        .then(Commands.literal("list").executes { listBattles(it.source) })
        .then(Commands.literal("end").then(Commands.argument("player", EntityArgument.player())
            .then(Commands.literal("forfeit").executes { endBattle(it.source, EntityArgument.getPlayer(it, "player"), forfeit = true) })
            .then(Commands.literal("void").executes { endBattle(it.source, EntityArgument.getPlayer(it, "player"), forfeit = false) })))
        .then(Commands.literal("pending")
            .executes { listPending(it.source, null) }
            .then(Commands.literal("list")
                .executes { listPending(it.source, null) }
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { command ->
                    profile(command)?.let { listPending(command.source, it) } ?: 0
                }))
            .then(pendingAction("retry") { source, admin, playerId -> admin.retryPending(source.server, playerId) })
            .then(pendingAction("drop") { source, admin, playerId -> admin.dropPending(source.server, playerId) }))

    private fun pendingAction(
        name: String,
        action: (CommandSourceStack, MccAdminSource, UUID?) -> Int,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        fun run(source: CommandSourceStack, player: GameProfile?): Int {
            val count = MccAdminSources.all().sumOf { admin -> safely(source, admin) { action(source, admin, player?.id) } ?: 0 }
            source.sendSuccess({ Component.translatable("$KEY.pending.$name", count, player?.name ?: "*") }, true)
            MoreCobblemonContents.LOGGER.info("MCC admin pending {} actor={} target={} count={}", name, source.textName, player?.id ?: "*", count)
            return 1
        }
        return Commands.literal(name)
            .executes { run(it.source, null) }
            .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes { command ->
                profile(command)?.let { run(command.source, it) } ?: 0
            })
    }

    private fun resetRecords(command: CommandContext<CommandSourceStack>, contentId: String?, formatId: String?): Int {
        val source = command.source
        val player = profile(command) ?: return 0
        val server = source.server
        if (!BattleRecordService.isAvailable(server)) {
            source.sendFailure(Component.translatable("$KEY.records.unavailable"))
            return 0
        }
        // A content holding live progress would write its old numbers back over the reset.
        val busy = MccAdminSources.all().filter { admin -> safely(source, admin) { admin.busy(server, player.id) } == true }
        if (busy.isNotEmpty() || BattleRegistry.getBattleByParticipatingPlayerId(player.id) != null) {
            val where = busy.fold(Component.empty()) { all, admin -> all.append(if (all.siblings.isEmpty()) "" else ", ").append(admin.label) }
            source.sendFailure(Component.translatable("$KEY.records.busy", player.name, where))
            return 0
        }
        val removed = BattleRecordService.delete(server, player.id, contentId, formatId)
        source.sendSuccess({
            Component.translatable("$KEY.records.reset", player.name, contentId ?: "*", formatId ?: "*", removed)
        }, true)
        MoreCobblemonContents.LOGGER.info("MCC admin records reset actor={} target={} content={} format={} removed={}",
            source.textName, player.id, contentId ?: "*", formatId ?: "*", removed)
        return 1
    }

    private fun listBattles(source: CommandSourceStack): Int {
        val ids = Cobblemon173BattleRuleHooks.registeredBattleIds()
        line(source, Component.translatable("$KEY.battle.header", ids.size))
        ids.forEach { battleId ->
            val battle = BattleRegistry.getBattle(battleId)
            val players = battle?.players?.joinToString(", ") { it.name.string }.orEmpty().ifEmpty { "-" }
            line(source, Component.translatable("$KEY.battle.entry", Cobblemon173BattleRuleHooks.contentId(battleId) ?: "-",
                players, battle?.turn ?: 0, battleId.toString().take(8)))
        }
        return 1
    }

    private fun endBattle(source: CommandSourceStack, player: ServerPlayer, forfeit: Boolean): Int {
        val battle = BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)
        if (battle == null) {
            source.sendFailure(Component.translatable("$KEY.battle.none", player.name.string))
            return 0
        }
        val ended = if (forfeit) {
            Cobblemon173BattleForfeit.request(player.uuid, battle.battleId)
        } else {
            Cobblemon173ManagedBattleTermination.end(battle.battleId)
            true
        }
        if (!ended) {
            source.sendFailure(Component.translatable("$KEY.battle.forfeit_failed", player.name.string))
            return 0
        }
        val mode = if (forfeit) "forfeit" else "void"
        source.sendSuccess({ Component.translatable("$KEY.battle.ended.$mode", player.name.string) }, true)
        player.sendSystemMessage(Component.translatable("$KEY.battle.ended_notice"))
        MoreCobblemonContents.LOGGER.info("MCC admin battle end actor={} target={} battle={} mode={} content={}",
            source.textName, player.uuid, battle.battleId, mode, Cobblemon173BattleRuleHooks.contentId(battle.battleId) ?: "-")
        return 1
    }

    private fun listPending(source: CommandSourceStack, player: GameProfile?): Int {
        val server = source.server
        val results = MccAdminSources.all().flatMap { admin ->
            (safely(source, admin) { admin.pending(server) } ?: emptyList())
                .filter { player == null || it.playerId == player.id }
                .map { admin to it }
        }
        line(source, Component.translatable("$KEY.pending.header", results.size))
        results.forEach { (admin, result) ->
            line(source, Component.empty().append("[").append(admin.label).append("] ").append(Component.translatable("$KEY.pending.entry",
                name(server, result.playerId), result.battleId?.toString()?.take(8) ?: "-", result.detail)))
        }
        return 1
    }

    private fun profile(command: CommandContext<CommandSourceStack>): GameProfile? = MccAdminArguments.profile(command)

    private fun name(server: MinecraftServer, playerId: UUID): String = MccAdminArguments.name(server, playerId)

    private fun available(value: Boolean): Component =
        Component.translatable(if (value) "$KEY.status.available" else "$KEY.status.unavailable")

    private fun line(source: CommandSourceStack, text: Component) = source.sendSuccess({ text }, false)

    /** Runs one source's part; a broken source is reported and skipped rather than failing the whole command. */
    private fun <T> safely(source: CommandSourceStack, admin: MccAdminSource, action: () -> T): T? = try {
        action()
    } catch (failure: RuntimeException) {
        MoreCobblemonContents.LOGGER.error("MCC admin source {} failed", admin.label.string, failure)
        source.sendFailure(Component.empty().append("[").append(admin.label).append("] ").append(Component.translatable("$KEY.source_failed")))
        null
    }

    private val CONTENTS = listOf(
        ManagedBattleContentIds.BATTLE_TOWER,
        ManagedBattleContentIds.BATTLE_FACTORY,
        ManagedBattleContentIds.PVP,
        ManagedBattleContentIds.LEAGUE_CHALLENGE,
    )
}
