package jbro.cobblemon.morebattlecontent.internal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
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
        "command.${MoreBattleContent.MOD_ID}.test.ai.difficulty.introductory",
        1,
        BattleDifficultyProfiles.INTRODUCTORY,
    ),
    STANDARD(
        "ai-표준",
        "command.${MoreBattleContent.MOD_ID}.test.ai.difficulty.standard",
        2,
        BattleDifficultyProfiles.STANDARD,
    ),
    ADVANCED(
        "ai-상급",
        "command.${MoreBattleContent.MOD_ID}.test.ai.difficulty.advanced",
        4,
        BattleDifficultyProfiles.ADVANCED,
    ),
    BOSS(
        "ai-보스",
        "command.${MoreBattleContent.MOD_ID}.test.ai.difficulty.boss",
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
        return root
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
                        "command.${MoreBattleContent.MOD_ID}.test.ai.started",
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
                    "command.${MoreBattleContent.MOD_ID}.test.ai.error.party_size",
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
        source.sendFailure(Component.translatable("command.${MoreBattleContent.MOD_ID}.test.ai.error.$suffix"))
        return 0
    }
}
