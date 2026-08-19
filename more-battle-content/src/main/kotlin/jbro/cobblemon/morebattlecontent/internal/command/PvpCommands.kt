package jbro.cobblemon.morebattlecontent.internal.command

import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import net.minecraft.server.level.ServerPlayer

internal enum class PvpCommandStatus {
    APPLIED,
    SELF_CHALLENGE,
    CLIENT_UNSUPPORTED,
    PARTICIPANT_BUSY,
    UNKNOWN_CHALLENGE,
    WRONG_CHALLENGER,
    TEAM_INVALID,
    OPPONENT_TEAM_INVALID,
    INVALID_STATE,
    INTERNAL_FAILURE,
}

internal data class PvpCommandOutcome(
    val status: PvpCommandStatus,
    val messageArgument: String? = null,
)

internal interface PvpCommandBackend {
    fun open(player: ServerPlayer): PvpCommandOutcome

    fun challenge(challenger: ServerPlayer, opponent: ServerPlayer, format: PvpBattleFormat): PvpCommandOutcome

    fun accept(opponent: ServerPlayer, challenger: ServerPlayer): PvpCommandOutcome

    fun decline(opponent: ServerPlayer, challenger: ServerPlayer): PvpCommandOutcome

    fun cancel(player: ServerPlayer): PvpCommandOutcome

}
