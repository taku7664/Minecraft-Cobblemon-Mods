package jbro.cobblemon.morebattlecontent.internal.pvp.ui

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat

internal data class PvpSelectionPartySlot(
    val pokemonId: UUID,
    val speciesId: String,
    val heldItemId: String?,
    val originalLevel: Int,
    val battleLevel: Int,
    val formId: String? = null,
)

/**
 * Opponent entries carry no Pokemon UUID because the client cannot look the opponent's Pokemon up
 * locally. Species and form are the public competitive information the preview is allowed to show.
 */
internal data class PvpSelectionOpponentSlot(
    val speciesId: String,
    val formId: String? = null,
)

internal data class PvpSelectionSpectator(val playerId: UUID, val name: String)

internal class PvpSelectionViewState(
    val matchId: UUID,
    val format: PvpBattleFormat,
    val opponentName: String,
    ownParty: Collection<PvpSelectionPartySlot>,
    opponentParty: Collection<PvpSelectionOpponentSlot>,
    selectedPokemonIds: Collection<UUID>,
    val selectionDeadlineEpochMillis: Long,
    val waitingForOpponent: Boolean,
    val battleStartRetryAvailable: Boolean = false,
    val playerOnLeft: Boolean = true,
    val leftPlayerName: String = "",
    val rightPlayerName: String = opponentName,
    spectators: Collection<PvpSelectionSpectator> = emptyList(),
    val spectatorMode: Boolean = false,
    spectatorLeftParty: Collection<PvpSelectionOpponentSlot> = emptyList(),
    spectatorRightParty: Collection<PvpSelectionOpponentSlot> = emptyList(),
) {
    val ownParty: List<PvpSelectionPartySlot> = Collections.unmodifiableList(ArrayList(ownParty))
    val opponentParty: List<PvpSelectionOpponentSlot> = Collections.unmodifiableList(ArrayList(opponentParty))
    val selectedPokemonIds: Set<UUID> = Collections.unmodifiableSet(LinkedHashSet(selectedPokemonIds))
    val spectators: List<PvpSelectionSpectator> = Collections.unmodifiableList(ArrayList(spectators))
    val spectatorLeftParty: List<PvpSelectionOpponentSlot> =
        Collections.unmodifiableList(ArrayList(spectatorLeftParty))
    val spectatorRightParty: List<PvpSelectionOpponentSlot> =
        Collections.unmodifiableList(ArrayList(spectatorRightParty))

    init {
        if (spectatorMode) {
            require(this.ownParty.isEmpty() && this.opponentParty.isEmpty() && this.selectedPokemonIds.isEmpty()) {
                "PvP spectator view cannot contain participant-private state"
            }
            require(this.spectatorLeftParty.size in format.registrationRange) { "PvP spectator view has an invalid left party size" }
            require(this.spectatorRightParty.size in format.registrationRange) { "PvP spectator view has an invalid right party size" }
        } else {
            require(this.ownParty.size in format.registrationRange) { "PvP view has an invalid registered party size" }
            require(this.opponentParty.size in format.registrationRange) { "PvP view has an invalid opponent party size" }
        }
        require(this.selectedPokemonIds.size <= format.selectionSize) { "PvP view has too many selected Pokemon" }
        require(this.ownParty.map(PvpSelectionPartySlot::pokemonId).containsAll(this.selectedPokemonIds)) {
            "PvP view selection contains an unregistered Pokemon"
        }
        require(selectionDeadlineEpochMillis >= 0) { "PvP selection deadline cannot be negative" }
    }

    val immutableOwnParty: List<PvpSelectionPartySlot> = this.ownParty
    val immutableOpponentParty: List<PvpSelectionOpponentSlot> = this.opponentParty
    val immutableSelectedPokemonIds: Set<UUID> = this.selectedPokemonIds
    val immutableSpectators: List<PvpSelectionSpectator> = this.spectators
    val immutableSpectatorLeftParty: List<PvpSelectionOpponentSlot> = this.spectatorLeftParty
    val immutableSpectatorRightParty: List<PvpSelectionOpponentSlot> = this.spectatorRightParty

    fun copy(
        matchId: UUID = this.matchId,
        format: PvpBattleFormat = this.format,
        opponentName: String = this.opponentName,
        ownParty: Collection<PvpSelectionPartySlot> = this.ownParty,
        opponentParty: Collection<PvpSelectionOpponentSlot> = this.opponentParty,
        selectedPokemonIds: Collection<UUID> = this.selectedPokemonIds,
        selectionDeadlineEpochMillis: Long = this.selectionDeadlineEpochMillis,
        waitingForOpponent: Boolean = this.waitingForOpponent,
        battleStartRetryAvailable: Boolean = this.battleStartRetryAvailable,
        playerOnLeft: Boolean = this.playerOnLeft,
        leftPlayerName: String = this.leftPlayerName,
        rightPlayerName: String = this.rightPlayerName,
        spectators: Collection<PvpSelectionSpectator> = this.spectators,
        spectatorMode: Boolean = this.spectatorMode,
        spectatorLeftParty: Collection<PvpSelectionOpponentSlot> = this.spectatorLeftParty,
        spectatorRightParty: Collection<PvpSelectionOpponentSlot> = this.spectatorRightParty,
    ) = PvpSelectionViewState(
        matchId,
        format,
        opponentName,
        ownParty,
        opponentParty,
        selectedPokemonIds,
        selectionDeadlineEpochMillis,
        waitingForOpponent,
        battleStartRetryAvailable,
        playerOnLeft,
        leftPlayerName,
        rightPlayerName,
        spectators,
        spectatorMode,
        spectatorLeftParty,
        spectatorRightParty,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is PvpSelectionViewState &&
            matchId == other.matchId &&
            format == other.format &&
            opponentName == other.opponentName &&
            ownParty == other.ownParty &&
            opponentParty == other.opponentParty &&
            selectedPokemonIds == other.selectedPokemonIds &&
            selectionDeadlineEpochMillis == other.selectionDeadlineEpochMillis &&
            waitingForOpponent == other.waitingForOpponent &&
            battleStartRetryAvailable == other.battleStartRetryAvailable &&
            playerOnLeft == other.playerOnLeft &&
            leftPlayerName == other.leftPlayerName &&
            rightPlayerName == other.rightPlayerName &&
            spectators == other.spectators &&
            spectatorMode == other.spectatorMode &&
            spectatorLeftParty == other.spectatorLeftParty &&
            spectatorRightParty == other.spectatorRightParty

    override fun hashCode(): Int {
        var result = matchId.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + opponentName.hashCode()
        result = 31 * result + ownParty.hashCode()
        result = 31 * result + opponentParty.hashCode()
        result = 31 * result + selectedPokemonIds.hashCode()
        result = 31 * result + selectionDeadlineEpochMillis.hashCode()
        result = 31 * result + waitingForOpponent.hashCode()
        result = 31 * result + battleStartRetryAvailable.hashCode()
        result = 31 * result + playerOnLeft.hashCode()
        result = 31 * result + leftPlayerName.hashCode()
        result = 31 * result + rightPlayerName.hashCode()
        result = 31 * result + spectators.hashCode()
        result = 31 * result + spectatorMode.hashCode()
        result = 31 * result + spectatorLeftParty.hashCode()
        result = 31 * result + spectatorRightParty.hashCode()
        return result
    }

    override fun toString(): String =
        "PvpSelectionViewState(matchId=$matchId, format=$format, opponentName=$opponentName, " +
            "ownParty=$ownParty, opponentParty=$opponentParty, selectedPokemonIds=$selectedPokemonIds, " +
            "selectionDeadlineEpochMillis=$selectionDeadlineEpochMillis, waitingForOpponent=$waitingForOpponent, " +
            "battleStartRetryAvailable=$battleStartRetryAvailable, playerOnLeft=$playerOnLeft, " +
            "leftPlayerName=$leftPlayerName, rightPlayerName=$rightPlayerName, spectators=$spectators, " +
            "spectatorMode=$spectatorMode, spectatorLeftParty=$spectatorLeftParty, " +
            "spectatorRightParty=$spectatorRightParty)"
}

internal sealed interface PvpSelectionIntent {
    val requestId: UUID
    val matchId: UUID

    data class Submit(
        override val requestId: UUID,
        override val matchId: UUID,
        val pokemonIds: List<UUID>,
    ) : PvpSelectionIntent

    data class Cancel(
        override val requestId: UUID,
        override val matchId: UUID,
    ) : PvpSelectionIntent

    data class Retry(
        override val requestId: UUID,
        override val matchId: UUID,
    ) : PvpSelectionIntent

    data class Unready(
        override val requestId: UUID,
        override val matchId: UUID,
    ) : PvpSelectionIntent
}
