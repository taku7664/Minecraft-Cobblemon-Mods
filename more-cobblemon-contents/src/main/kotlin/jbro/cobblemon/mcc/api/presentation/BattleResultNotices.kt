package jbro.cobblemon.mcc.api.presentation

import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * The chat line a player gets when a battle ends, the same for every content: who they beat and the BP they got,
 * or who beat them. Contents call it once per player after the result is saved.
 */
object BattleResultNotices {
    private const val KEY = "message.${MoreCobblemonContents.MOD_ID}.battle_result"

    /** [player] beat [opponent] and got [bp] BP; with no BP the line only names the opponent. */
    fun victory(player: ServerPlayer, opponent: Component, bp: Long = 0) {
        player.sendSystemMessage(victoryLine(opponent, bp))
    }

    fun defeat(player: ServerPlayer, opponent: Component) {
        player.sendSystemMessage(defeatLine(opponent))
    }

    fun victoryLine(opponent: Component, bp: Long = 0): Component {
        val name = opponent.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
        return if (bp > 0) {
            Component.translatable("$KEY.victory_bp", name, Component.literal("+$bp BP").withStyle(ChatFormatting.GOLD))
                .withStyle(ChatFormatting.GREEN)
        } else {
            Component.translatable("$KEY.victory", name).withStyle(ChatFormatting.GREEN)
        }
    }

    fun defeatLine(opponent: Component): Component =
        Component.translatable("$KEY.defeat", opponent.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .withStyle(ChatFormatting.RED)
}
