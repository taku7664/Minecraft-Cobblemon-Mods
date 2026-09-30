package jbro.cobblemon.mcc.internal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfile
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import com.cobblemon.mod.common.battles.BattleRegistry
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal enum class AiTestDifficulty(
    val commandLiteral: String,
    val translationKey: String,
    val skillLevel: Int,
    val difficulty: BattleDifficultyProfile,
) {
    INTRODUCTORY(
        "ai-입문",
        "command.${MoreCobblemonContents.MOD_ID}.test.ai.difficulty.introductory",
        1,
        BattleDifficultyProfiles.INTRODUCTORY,
    ),
    STANDARD(
        "ai-표준",
        "command.${MoreCobblemonContents.MOD_ID}.test.ai.difficulty.standard",
        2,
        BattleDifficultyProfiles.STANDARD,
    ),
    ADVANCED(
        "ai-상급",
        "command.${MoreCobblemonContents.MOD_ID}.test.ai.difficulty.advanced",
        4,
        BattleDifficultyProfiles.ADVANCED,
    ),
    BOSS(
        "ai-보스",
        "command.${MoreCobblemonContents.MOD_ID}.test.ai.difficulty.boss",
        5,
        BattleDifficultyProfiles.BOSS,
    ),
    ;

    fun trainerProfile(): BattleTrainerProfile = BattleTrainerProfile(
        skillLevel = skillLevel,
        personality = BattleTrainerProfile.champion().personality,
        difficulty = difficulty,
    )
}

internal sealed interface AiTestBattleStartResult {
    data class Started(val battleId: UUID) : AiTestBattleStartResult
    data class WrongPartySize(val actual: Int) : AiTestBattleStartResult
    data object AlreadyInBattle : AiTestBattleStartResult
    data object BetterAiUnavailable : AiTestBattleStartResult
    data object Unavailable : AiTestBattleStartResult
}

internal fun interface AiTestCommandBackend {
    fun start(player: ServerPlayer, difficulty: AiTestDifficulty): AiTestBattleStartResult
}

internal object AiTestCommands {
    const val ADMIN_PERMISSION_LEVEL: Int = 2

    fun build(
        backend: AiTestCommandBackend = AiTestCommandBackend { _, _ -> AiTestBattleStartResult.Unavailable },
    ): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("test").requires { source -> source.hasPermission(ADMIN_PERMISSION_LEVEL) }
        AiTestDifficulty.entries.forEach { difficulty ->
            root.then(
                Commands.literal(difficulty.commandLiteral).executes { command ->
                    respond(command.source, difficulty, backend.start(command.source.playerOrException, difficulty))
                },
            )
        }
        root.then(
            Commands.literal("stop")
                .executes { command -> stop(command.source, command.source.playerOrException) }
                .then(Commands.argument("player", EntityArgument.player()).executes { command ->
                    stop(command.source, EntityArgument.getPlayer(command, "player"))
                }),
        )
        return root
    }

    /** Ends [player]'s AI test battle without a result; the test keeps no progress to settle. */
    private fun stop(source: CommandSourceStack, player: ServerPlayer): Int {
        val battle = BattleRegistry.getBattleByParticipatingPlayerId(player.uuid)
        if (battle == null || Cobblemon173BattleRuleHooks.contentId(battle.battleId) != ManagedBattleContentIds.AI_TEST) {
            source.sendFailure(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.test.ai.error.no_test_battle", player.name.string))
            return 0
        }
        Cobblemon173ManagedBattleTermination.end(battle.battleId)
        source.sendSuccess({ Component.translatable("command.${MoreCobblemonContents.MOD_ID}.test.ai.stopped", player.name.string) }, true)
        return 1
    }

    private fun respond(
        source: CommandSourceStack,
        difficulty: AiTestDifficulty,
        result: AiTestBattleStartResult,
    ): Int = when (result) {
        is AiTestBattleStartResult.Started -> {
            source.sendSuccess(
                {
                    Component.translatable(
                        "command.${MoreCobblemonContents.MOD_ID}.test.ai.started",
                        Component.translatable(difficulty.translationKey),
                    )
                },
                false,
            )
            1
        }

        is AiTestBattleStartResult.WrongPartySize -> {
            source.sendFailure(
                Component.translatable(
                    "command.${MoreCobblemonContents.MOD_ID}.test.ai.error.party_size",
                    result.actual,
                ),
            )
            0
        }

        AiTestBattleStartResult.AlreadyInBattle -> failure(source, "active_battle")
        AiTestBattleStartResult.BetterAiUnavailable -> failure(source, "better_ai_unavailable")
        AiTestBattleStartResult.Unavailable -> failure(source, "unavailable")
    }

    private fun failure(source: CommandSourceStack, suffix: String): Int {
        source.sendFailure(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.test.ai.error.$suffix"))
        return 0
    }
}
