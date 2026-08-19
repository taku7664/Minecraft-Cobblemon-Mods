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
)

internal data class PvpSelectionSpectator(val playerId: UUID, val name: String)

internal data class PvpSelectionViewState(
    val matchId: UUID,
    val format: PvpBattleFormat,
    val opponentName: String,
    val ownParty: List<PvpSelectionPartySlot>,
    val opponentSpeciesIds: List<String>,
    val selectedPokemonIds: Set<UUID>,
    val selectionDeadlineEpochMillis: Long,
    val waitingForOpponent: Boolean,
    val battleStartRetryAvailable: Boolean = false,
    val playerOnLeft: Boolean = true,
    val leftPlayerName: String = "",
    val rightPlayerName: String = opponentName,
    val spectators: List<PvpSelectionSpectator> = emptyList(),
) {
    init {
        require(ownParty.size in format.registrationRange) { "PvP view has an invalid registered party size" }
        require(opponentSpeciesIds.size in format.registrationRange) { "PvP view has an invalid opponent party size" }
        require(selectedPokemonIds.size <= format.selectionSize) { "PvP view has too many selected Pokemon" }
        require(ownParty.map(PvpSelectionPartySlot::pokemonId).containsAll(selectedPokemonIds)) {
            "PvP view selection contains an unregistered Pokemon"
        }
        require(selectionDeadlineEpochMillis >= 0) { "PvP selection deadline cannot be negative" }
    }

    val immutableOwnParty: List<PvpSelectionPartySlot> = Collections.unmodifiableList(ArrayList(ownParty))
    val immutableOpponentSpeciesIds: List<String> = Collections.unmodifiableList(ArrayList(opponentSpeciesIds))
    val immutableSelectedPokemonIds: Set<UUID> = Collections.unmodifiableSet(LinkedHashSet(selectedPokemonIds))
    val immutableSpectators: List<PvpSelectionSpectator> = Collections.unmodifiableList(ArrayList(spectators))
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
