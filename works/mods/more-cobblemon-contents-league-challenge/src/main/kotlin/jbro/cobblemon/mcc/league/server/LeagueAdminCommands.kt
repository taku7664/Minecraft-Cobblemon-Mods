package jbro.cobblemon.mcc.league.server

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import java.util.UUID
import jbro.cobblemon.mcc.internal.command.BattleProgressCommands
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import jbro.cobblemon.mcc.internal.command.MccAdminSource
import jbro.cobblemon.mcc.internal.command.MccAdminSources
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import jbro.cobblemon.mcc.internal.command.MccPendingResult
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.system.LeagueCatalog
import jbro.cobblemon.mcc.league.system.LeagueEngine
import jbro.cobblemon.mcc.league.system.LeagueProgress
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import jbro.cobblemon.mcc.api.battle.ManagedPveBattles
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * `/mcc league` for operators: a player's full progress, their undelivered rewards and run, their level cap, the
 * League's setup checks and catalog, and the badge import. `check champion|hard|badges <min> [player]` answers 1 or 0,
 * so an NPC dialogue (`cmd:mcc league check badges 4`) or a command block can branch on League progress.
 */
object LeagueAdminCommands {
    private const val KEY = "command.${Mod.MOD_ID}.admin"
    private const val ERROR_PREFIX = "message.${Mod.MOD_ID}."

    fun register() {
        MccCommandContributors.register { build() }
        MccAdminSources.register(source)
    }

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = operations().then(literal("region").then(
        argument("region", StringArgumentType.word()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(LeagueCatalogResources.catalogs.keys.map { it.substringAfter(':') }, builder)
        }.apply { operations().build().children.forEach { then(it) } }))

    private fun operations(): LiteralArgumentBuilder<CommandSourceStack> = literal("league")
        .requires(BattleProgressCommands::isAdmin)
        .then(check())
        .then(literal("inspect").then(profileArgument().executes { withProgress(it) { catalog, id, name, state -> inspect(it.source, catalog, name, state) } }))
        .then(literal("rewards")
            .then(literal("list").then(profileArgument().executes { withProgress(it) { _, _, name, state -> listRewards(it.source, name, state) } }))
            .then(literal("retry").then(argument("player", EntityArgument.player()).executes { context ->
                val player = EntityArgument.getPlayer(context, "player")
                val failure = LeagueServer.adminReconcile(player)
                if (failure != null) {
                    context.source.sendFailure(Component.translatable("$KEY.rewards.retry_failed", player.name.string,
                        Component.translatableWithFallback(ERROR_PREFIX + failure, failure)))
                    return@executes 0
                }
                context.source.sendSuccess({ Component.translatable("$KEY.rewards.retried", player.name.string) }, true)
                1
            }))
            .then(literal("drop").then(profileArgument().executes { withProgress(it) { catalog, id, name, state ->
                val dropped = state.rewards.count { reward -> !reward.badgeDone || !reward.bpDone }
                if (dropped == 0) {
                    it.source.sendFailure(Component.translatable("$KEY.rewards.none", name))
                    return@withProgress 0
                }
                LeagueServer.adminCommit(it.source.server, catalog, id, markDelivered(state))
                it.source.sendSuccess({ Component.translatable("$KEY.rewards.dropped", dropped, name) }, true)
                Mod.LOGGER.info("MCC admin league rewards drop actor={} target={} count={}", it.source.textName, id, dropped)
                1
            } })))
        .then(literal("run")
            .then(literal("cancel").then(profileArgument().executes { withProgress(it) { catalog, id, name, _ ->
                if (!LeagueServer.adminCancelRun(it.source.server, catalog, id)) {
                    it.source.sendFailure(Component.translatable("$KEY.run.none", name))
                    return@withProgress 0
                }
                it.source.sendSuccess({ Component.translatable("$KEY.run.cancelled", name) }, true)
                it.source.server.playerList.getPlayer(id)?.sendSystemMessage(Component.translatable("$KEY.run.notice"))
                Mod.LOGGER.info("MCC admin league run cancel actor={} target={}", it.source.textName, id)
                1
            } })))
        .then(literal("progress")
            .then(literal("set").then(argument("player", EntityArgument.player())
                .then(argument("challenge", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(progressTargets(), builder) }
                    .executes { context -> setProgress(context, StringArgumentType.getString(context, "challenge")) })))
            .then(literal("reset").then(argument("player", EntityArgument.player()).executes { context -> setProgress(context, null) })))
        .then(literal("test").then(argument("player", EntityArgument.player())
            // Korean names first (강석, 강석-하드): Brigadier's unquoted words are ASCII only, so each is its own literal.
            .apply { KOREAN_TARGETS.forEach { (name, target) -> then(literal(name).apply { testDifficulties { target.word } }) } }
            .then(argument("challenge", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(progressTargets().filter { it != ALL }, builder) }
                .apply { testDifficulties { StringArgumentType.getString(it, "challenge") } })))
        .then(literal("cap").then(literal("sync").then(argument("player", EntityArgument.player()).executes { context ->
            val player = EntityArgument.getPlayer(context, "player")
            val catalog = catalog(context) ?: return@executes 0
            val cap = LeagueProgressQueries.earnedCap(player.server, player.uuid)
            try {
                LeagueIntegrations.syncCap(player, cap)
            } catch (failure: RuntimeException) {
                context.source.sendFailure(Component.translatable("$KEY.cap.failed", player.name.string, reason(failure)))
                return@executes 0
            }
            context.source.sendSuccess({ Component.translatable("$KEY.cap.synced", player.name.string, cap) }, true)
            1
        })))
        .then(literal("validate").executes { context ->
            val catalog = catalog(context) ?: return@executes 0
            val problem = validation(catalog)
            if (problem != null) {
                context.source.sendFailure(Component.translatable("$KEY.validate.failed", problem))
                return@executes 0
            }
            context.source.sendSuccess({ Component.translatable("$KEY.validate.ok") }, false)
            1
        })
        .then(jbro.cobblemon.mcc.league.trainer.WildTrainerCommands.build())
        .then(literal("catalog").executes { context ->
            catalogStatus().forEach { line -> context.source.sendSuccess({ line }, false) }
            1
        })
        .then(literal("import-badges").then(argument("player", EntityArgument.player()).executes { context ->
            val target = EntityArgument.getPlayer(context, "player")
            val catalog = catalog(context) ?: return@executes 0
            val before = LeagueSavedData.get(target.server).read(catalog.id, target.uuid)
            if (before.run != null) {
                context.source.sendFailure(Component.translatable("message.${Mod.MOD_ID}.run_active"))
                return@executes 0
            }
            val imported = catalog.gyms.filter { LeagueIntegrations.hasBadge(target, requireNotNull(catalog.challenges.getValue(it).badge)) }
            // Explicit operator migration only. It never awards BP or a Champion title.
            val after = before.copy(revision = before.revision + 1, cleared = before.cleared + imported,
                unlockedCap = maxOf(before.unlockedCap, imported.maxOfOrNull { catalog.challenges.getValue(it).unlockCap } ?: 0))
            LeagueServer.adminCommit(target.server, catalog, target.uuid, after)
            Mod.LOGGER.info("League badge import by {} for {}: {}", context.source.textName, target.uuid, imported)
            context.source.sendSuccess({ Component.translatable("command.${Mod.MOD_ID}.imported", imported.size, target.scoreboardName) }, true)
            1
        }))

    /**
     * The challenges `progress set` and `test` take, by their ID without the namespace, and `all`. A region's `/`
     * reads as `.` (`galar.cup_marnie`): Brigadier's unquoted words take no `/`.
     */
    private fun progressTargets(): List<String> =
        LeagueCatalogResources.catalogs.values.flatMap { catalog -> LeagueEngine(catalog).route().map { word(it) } }.distinct() + ALL

    private fun word(challengeId: String): String = challengeId.substringAfter(':').replace('/', '.')

    /**
     * The catalog and challenge ID [target] names: a [progressTargets] word, or a Korean name's word. A `region`
     * argument picks the catalog; without one, the catalog whose route holds it, the primary League first.
     */
    private fun route(context: CommandContext<CommandSourceStack>, target: String): Pair<LeagueCatalog, String>? {
        val explicit = runCatching { StringArgumentType.getString(context, "region") }.getOrNull() != null
        val primary = LeagueCatalogResources.current
        val catalogs = if (explicit) listOfNotNull(catalog(context))
            else listOfNotNull(primary) + LeagueCatalogResources.catalogs.values.filter { it != primary }
        val named = KOREAN_TARGETS.values.firstOrNull { it.word == target }
        catalogs.forEach { catalog ->
            val route = LeagueEngine(catalog).route()
            val found = named?.find(catalog, route) ?: route.firstOrNull { word(it) == target }.takeIf { named == null }
            if (found != null) return catalog to found
        }
        context.source.sendFailure(Component.translatable("$KEY.progress.unknown", target))
        return null
    }

    /**
     * Sets an online player's progress for testing: everything before [target] beaten, everything with `all`, nothing
     * when [target] is null (reset). The normal gyms' badges follow, given or taken back; the level cap follows through
     * the commit's reconcile. No BP is paid.
     */
    private fun setProgress(context: CommandContext<CommandSourceStack>, target: String?): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val (catalog, challengeId) = when (target) {
            null, ALL -> (catalog(context) ?: return 0).let { catalog ->
                catalog to if (target == null) LeagueEngine(catalog).route().first() else null
            }
            else -> route(context, target) ?: return 0
        }
        val engine = LeagueEngine(catalog)
        val before = LeagueSavedData.get(player.server).read(catalog.id, player.uuid)
        if (before.run != null) {
            context.source.sendFailure(Component.translatable("message.${Mod.MOD_ID}.run_active"))
            return 0
        }
        val after = engine.clearedBefore(before, challengeId, System.currentTimeMillis())
        LeagueServer.adminCommit(player.server, catalog, player.uuid, after)
        catalog.gyms.forEach { gym ->
            LeagueIntegrations.setBadge(player, requireNotNull(catalog.challenges.getValue(gym).badge), gym in after.cleared)
        }
        val cap = engine.cap(after)
        val name = player.name.string
        context.source.sendSuccess({
            when (target) {
                null -> Component.translatable("$KEY.progress.reset", name, cap)
                ALL -> Component.translatable("$KEY.progress.all", name, cap)
                else -> Component.translatable("$KEY.progress.set", name, target, cap, engine.badgeCount(after))
            }
        }, true)
        Mod.LOGGER.info("MCC admin league progress actor={} target={} before={}", context.source.textName, player.uuid, target ?: "reset")
        return 1
    }

    private const val ALL = "all"

    /** The AI levels `test` takes, named as `/mcc test` names them, and the trainer skill each stands for. */
    private val TEST_DIFFICULTIES = listOf("ai-입문" to 1, "ai-표준" to 2, "ai-상급" to 4, "ai-보스" to 5)

    private fun <T : com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T.testDifficulties(
        target: (CommandContext<CommandSourceStack>) -> String,
    ): T = apply { TEST_DIFFICULTIES.forEach { (name, skill) -> then(literal(name).executes { testBattle(it, target(it), name, skill) }) } }

    /**
     * A Korean `test` name: the trainer name key it stands for, whether it asks for the hard route and whether for the
     * Champion Cup's rematch, which shares its gym leader's name. [word] is what the literal hands to [route].
     */
    private class KoreanTarget(val nameKey: String, val hard: Boolean, val cup: Boolean, val word: String) {
        /** This trainer's challenge in [route]; a name met only in the Cup (마리) finds its Cup challenge. */
        fun find(catalog: LeagueCatalog, route: List<String>): String? {
            fun match(cup: Boolean) = route.firstOrNull { id ->
                catalog.challenges.getValue(id).nameKey == nameKey && id.endsWith("_hard") == hard &&
                    id.substringAfter(':').substringAfterLast('/').startsWith("cup_") == cup
            }
            return match(cup) ?: if (!cup) match(true) else null
        }
    }

    /**
     * The challenges by their Korean trainer names, for `test`: `강석`, `강석-하드`, and for a gym leader met again in
     * the Champion Cup `야청-컵`, `야청-컵-하드`. Read from the mod's own language file, since the command tree is built
     * before the League's data loads; each name finds its challenge in the loaded routes when it runs.
     */
    private val KOREAN_TARGETS: Map<String, KoreanTarget> by lazy {
        val stream = LeagueAdminCommands::class.java.classLoader.getResourceAsStream("assets/${Mod.MOD_ID}/lang/ko_kr.json")
            ?: return@lazy emptyMap()
        val names = stream.reader(Charsets.UTF_8).use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
        val trainer = Regex("""trainer\.${Regex.escape(Mod.MOD_ID)}\.(?:galar\.)?[a-z_]+""")
        names.entrySet().filter { (key, value) -> trainer.matches(key) && value.asString.none(Char::isWhitespace) }
            .flatMap { (key, value) ->
                val name = value.asString
                listOf("" to (false to false), "-하드" to (true to false), "-컵" to (false to true), "-컵-하드" to (true to true))
                    .map { (suffix, flags) -> "$name$suffix" to KoreanTarget(key, flags.first, flags.second, "ko:$name$suffix") }
            }.toMap()
    }

    /**
     * Starts a test battle using an online player's party and one League challenge's team, cap, gimmick and skin,
     * at the AI level [difficulty] names. It is an AI test
     * battle: no progress, reward or record (a League run in progress stays as it was), and
     * `/mcc battle end <player> void` ends it.
     */
    private fun testBattle(context: CommandContext<CommandSourceStack>, target: String, difficulty: String, skill: Int): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val (catalog, challengeId) = route(context, target) ?: return 0
        val engine = LeagueEngine(catalog)
        val challenge = catalog.challenges.getValue(challengeId)
        // The gimmick a real run of this League allows the player now, as LeagueServer starts one with.
        val mechanic = engine.mechanic(LeagueSavedData.get(player.server).read(catalog.id, player.uuid))
        val opponent = Component.translatable(challenge.nameKey)
        val name = player.name.string
        val id = try {
            // The AI test persona makes the brain record its decisions under logs/betterai-decisions.
            ManagedPveBattles.start(player, ManagedPveBattles.Request(UUID.randomUUID(), ManagedBattleContentIds.AI_TEST,
                BattleBrainContentIds.AI_TEST_PERSONA_PREFIX + challengeId.substringAfter(':'), challenge.nameKey, ManagedPveBattles.snapshotParty(player, MAX_LEVEL), challenge.team,
                ManagedPveBattles.Format.valueOf(challenge.format),
                jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.fromMajor(
                    mechanic.takeUnless { it == "NONE" }?.let(MajorBattleMechanic::valueOf)), skill = skill,
                appearance = challenge.skin?.let { TrainerResourceSkin(it, challenge.slim) },
                stage = engine.stage(challengeId),
                // The brain logs under the AI test id; the clients still hear and see the league battle.
                clientTag = MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, engine.stage(challengeId), challengeId), scenes = trainerScenes(challenge)), playerLevelCap = engine.battleCap(challengeId)) { outcome ->
                player.server.playerList.getPlayer(player.uuid)?.sendSystemMessage(
                    Component.translatable("$KEY.test.${outcome.name.lowercase()}", opponent, target))
            }
        } catch (failure: RuntimeException) {
            context.source.sendFailure(Component.translatable("$KEY.test.failed", name, reason(failure)))
            return 0
        }
        if (id == null) {
            context.source.sendFailure(Component.translatable("$KEY.test.busy", name))
            return 0
        }
        context.source.sendSuccess({ Component.translatable("$KEY.test.started", name, opponent, target, difficulty) }, true)
        Mod.LOGGER.info("MCC admin league test actor={} target={} challenge={} skill={}", context.source.textName, player.uuid, challengeId, skill)
        return 1
    }

    private const val MAX_LEVEL = 100

    /** The shared operator view: the catalog and setup in `/mcc status`, undelivered rewards in `/mcc battle pending`. */
    private val source = object : MccAdminSource {
        override val label: Component = Component.translatable("$KEY.label")

        override fun status(server: MinecraftServer): List<Component> {
            val catalog = LeagueCatalogResources.current ?: return catalogStatus()
            return catalogStatus() + listOf(validation(catalog)?.let { Component.translatable("$KEY.validate.failed", it) }
                ?: Component.translatable("$KEY.validate.ok"))
        }

        override fun pending(server: MinecraftServer): List<MccPendingResult> {
            return LeagueCatalogResources.catalogs.values.flatMap { catalog -> LeagueSavedData.get(server).all(catalog.id).flatMap { (id, state) ->
                state.rewards.filter { !it.badgeDone || !it.bpDone }.map { reward ->
                    MccPendingResult(id, null, "${reward.challengeId} badge=${reward.badgeDone} bp=${reward.bpDone}")
                }
            } }
        }

        override fun retryPending(server: MinecraftServer, playerId: UUID?): Int =
            pending(server).map { it.playerId }.distinct().filter { playerId == null || it == playerId }
                .mapNotNull(server.playerList::getPlayer).count { LeagueServer.adminReconcile(it) == null }

        override fun dropPending(server: MinecraftServer, playerId: UUID?): Int {
            return LeagueCatalogResources.catalogs.values.sumOf { catalog -> LeagueSavedData.get(server).all(catalog.id).filter { (id, _) -> playerId == null || id == playerId }.sumOf { (id, state) ->
                val count = state.rewards.count { !it.badgeDone || !it.bpDone }
                if (count > 0) LeagueServer.adminCommit(server, catalog, id, markDelivered(state))
                count
            } }
        }

        override fun busy(server: MinecraftServer, playerId: UUID): Boolean = false
    }

    private fun check() = literal("check")
        .then(checkTarget(literal("champion")) { _, state -> state.champion })
        .then(checkTarget(literal("hard")) { _, state -> state.hardChampion })
        .then(literal("badges").then(checkTarget(argument("min", IntegerArgumentType.integer(0, 8))) { context, state ->
            LeagueEngine(catalog(context) ?: return@checkTarget false).badgeCount(state) >=
                IntegerArgumentType.getInteger(context, "min")
        }))

    /** [node] run on the source player, or with a `player` argument after it; 1 when [holds], 0 when not. */
    private fun <T : com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> checkTarget(
        node: T,
        holds: (CommandContext<CommandSourceStack>, LeagueProgress) -> Boolean,
    ): T = node
        .executes { context -> check(context, context.source.playerOrException, holds) }
        .then(argument("player", EntityArgument.player())
            .executes { context -> check(context, EntityArgument.getPlayer(context, "player"), holds) })

    private fun check(
        context: CommandContext<CommandSourceStack>,
        player: net.minecraft.server.level.ServerPlayer,
        holds: (CommandContext<CommandSourceStack>, LeagueProgress) -> Boolean,
    ): Int {
        val catalog = catalog(context) ?: return 0
        val state = try {
            LeagueSavedData.get(context.source.server).read(catalog.id, player.uuid)
        } catch (failure: RuntimeException) {
            context.source.sendFailure(Component.translatable("$KEY.storage_unavailable"))
            return 0
        }
        val passed = holds(context, state)
        val report = Component.translatable("$KEY.check.${if (passed) "passed" else "failed"}", player.name,
            LeagueEngine(catalog).badgeCount(state), state.champion.toString(), state.hardChampion.toString())
        if (passed) context.source.sendSuccess({ report }, false) else context.source.sendFailure(report)
        return if (passed) 1 else 0
    }

    private fun profileArgument() = argument("player", GameProfileArgument.gameProfile())

    /** Runs [action] on the named player's progress in the current League, reporting what is missing first. */
    private fun withProgress(
        context: CommandContext<CommandSourceStack>,
        action: (LeagueCatalog, UUID, String, LeagueProgress) -> Int,
    ): Int {
        val profile = MccAdminArguments.profile(context) ?: return 0
        val catalog = catalog(context) ?: return 0
        val state = try {
            LeagueSavedData.get(context.source.server).read(catalog.id, profile.id)
        } catch (failure: RuntimeException) {
            context.source.sendFailure(Component.translatable("$KEY.storage_unavailable"))
            return 0
        }
        return action(catalog, profile.id, profile.name, state)
    }

    private fun inspect(source: CommandSourceStack, catalog: LeagueCatalog, name: String, state: LeagueProgress): Int {
        val engine = LeagueEngine(catalog)
        fun names(ids: List<String>) = ids.filter { it in state.cleared }.joinToString(", ") { it.substringAfter(':') }.ifEmpty { "-" }
        val lines = mutableListOf(
            Component.translatable("$KEY.inspect.header", name, catalog.id),
            Component.translatable("$KEY.inspect.summary", engine.badgeCount(state), engine.cap(state),
                state.champion.toString(), state.hardChampion.toString(), state.revision),
            Component.translatable("$KEY.inspect.cleared", names(catalog.gyms + catalog.finals)),
        )
        if (catalog.hasHard) lines += Component.translatable("$KEY.inspect.cleared_hard", names(catalog.hardGyms + catalog.hardFinals))
        val run = state.run
        lines += if (run == null) Component.translatable("$KEY.inspect.no_run")
            else Component.translatable("$KEY.inspect.run", run.challengeId.substringAfter(':'), run.index + 1, run.encounters.size,
                run.awaitingNext.toString(), run.party.size)
        lines += Component.translatable("$KEY.inspect.rewards", state.rewards.count { !it.badgeDone || !it.bpDone })
        lines.forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    private fun listRewards(source: CommandSourceStack, name: String, state: LeagueProgress): Int {
        val pending = state.rewards.filter { !it.badgeDone || !it.bpDone }
        source.sendSuccess({ Component.translatable("$KEY.rewards.header", name, pending.size) }, false)
        pending.forEach { reward ->
            source.sendSuccess({
                Component.translatable("$KEY.rewards.entry", reward.challengeId.substringAfter(':'), reward.badge ?: "-",
                    reward.badgeDone.toString(), reward.bp, reward.bpDone.toString())
            }, false)
        }
        return 1
    }

    /** Every undelivered reward marked delivered, so the player can challenge again; nothing is awarded. */
    private fun markDelivered(state: LeagueProgress) = state.copy(revision = state.revision + 1,
        rewards = state.rewards.map { it.copy(badgeDone = true, bpDone = true) })

    private fun catalog(context: CommandContext<CommandSourceStack>): LeagueCatalog? {
        val selected = runCatching { StringArgumentType.getString(context, "region") }.getOrNull()
            ?: return catalog(context.source)
        val id = if (':' in selected) selected else Mod.MOD_ID + ":" + selected
        return LeagueCatalogResources.find(id) ?: run {
            context.source.sendFailure(Component.translatable("$KEY.catalog.missing", id))
            null
        }
    }

    private fun catalog(source: CommandSourceStack): LeagueCatalog? = LeagueCatalogResources.current ?: run {
        source.sendFailure(Component.translatable("$KEY.catalog.missing", LeagueCatalogResources.lastFailure ?: "-"))
        null
    }

    private fun catalogStatus(): List<Component> {
        val catalog = LeagueCatalogResources.current
        val failure = LeagueCatalogResources.lastFailure
        return listOfNotNull(
            if (catalog == null) Component.translatable("$KEY.catalog.missing", failure ?: "-")
            else Component.translatable("$KEY.catalog.loaded", catalog.id, LeagueCatalogResources.revision, catalog.challenges.size,
                catalog.hasHard.toString()),
            failure?.takeIf { catalog != null }?.let { Component.translatable("$KEY.catalog.last_failure", it) },
        )
    }

    /** What stops the League's level caps from working, or null when the setup is sound. */
    private fun validation(catalog: LeagueCatalog): Component? = try {
        LeagueIntegrations.validateCaps(catalog)
        null
    } catch (failure: RuntimeException) {
        reason(failure)
    } catch (failure: LinkageError) {
        Component.literal(failure.javaClass.simpleName)
    }

    private fun reason(failure: RuntimeException): Component =
        Component.translatableWithFallback(ERROR_PREFIX + (failure.message?.substringBefore(':') ?: "request_failed"), failure.message ?: "-")
}
