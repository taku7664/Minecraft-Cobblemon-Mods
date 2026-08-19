package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_BATTLE_LEVEL_CAP
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_REGISTERED_TEAM_SIZE
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal enum class TowerPlayPhase {
    SELECTING,
    TEAM_LOCKED,
    ACTIVE,
}

internal sealed interface TowerPlayEntryContext {
    val entryContextId: UUID

    data class Command(
        override val entryContextId: UUID,
    ) : TowerPlayEntryContext

    data class VerifiedTerminal(
        override val entryContextId: UUID,
        val terminalId: UUID,
        val dimensionId: String,
        val x: Int,
        val y: Int,
        val z: Int,
    ) : TowerPlayEntryContext {
        init {
            require(IdentifierSyntax.isResourceId(dimensionId)) { "Invalid terminal dimension ID: $dimensionId" }
        }
    }
}

internal data class TowerPlayPartySlot(
    val slot: Int,
    val pokemonId: UUID,
    val speciesId: String,
    val heldItemId: String?,
    val level: Int,
    val battleLevel: Int,
) {
    init {
        require(slot in PARTY_SLOT_RANGE) { "Party slot must be within the registered team" }
        require(IdentifierSyntax.isResourceId(speciesId)) { "Invalid species ID: $speciesId" }
        require(heldItemId == null || IdentifierSyntax.isResourceId(heldItemId)) {
            "Invalid held item ID: $heldItemId"
        }
        require(level in LEVEL_RANGE) { "Pokemon level must be between 1 and 100" }
        require(battleLevel in LEVEL_RANGE && battleLevel <= level && battleLevel <= TOWER_BATTLE_LEVEL_CAP) {
            "Battle level must be valid, no greater than the original level, and capped at $TOWER_BATTLE_LEVEL_CAP"
        }
    }
}

internal class TowerPlayViewState(
    val entryContextId: UUID,
    val revision: Long,
    val format: TowerBattleFormat,
    val phase: TowerPlayPhase,
    party: Collection<TowerPlayPartySlot>,
    selectedPokemonIds: Collection<UUID>,
    val rank: TowerRank,
    val rankPoints: Int,
    val winsRequired: Int,
    val masterCycleWins: Int,
    val bpBalance: Long,
    errorKeys: Collection<String>,
    val selectedMechanic: MajorBattleMechanic? = null,
    val mechanicLocked: Boolean = false,
) {
    val party: List<TowerPlayPartySlot> = party.immutableList()
    val selectedPokemonOrder: List<UUID> = selectedPokemonIds.immutableList()
    val selectedPokemonIds: Set<UUID> = selectedPokemonOrder.immutableSet()
    val errorKeys: List<String> = errorKeys.immutableList()

    init {
        require(revision >= 0) { "Revision cannot be negative" }
        require(this.party.size <= TOWER_REGISTERED_TEAM_SIZE) { "Party cannot exceed the registered team size" }
        require(this.party.map { it.slot }.distinct().size == this.party.size) { "Party slots must be unique" }
        require(this.party.map { it.pokemonId }.distinct().size == this.party.size) {
            "Party Pokemon IDs must be unique"
        }
        require(selectedPokemonOrder.size == this.selectedPokemonIds.size) {
            "Selected Pokemon order cannot contain duplicate IDs"
        }
        val partyIds = this.party.mapTo(HashSet()) { it.pokemonId }
        require(this.selectedPokemonIds.all(partyIds::contains)) { "Selected Pokemon must belong to the party" }
        require(this.selectedPokemonIds.size <= format.selectionSize) {
            "Selection cannot exceed the battle format limit"
        }
        require(rankPoints in 0..winsRequired) { "Rank points must fit the displayed rank requirement" }
        require(winsRequired > 0) { "Wins required must be positive" }
        require(masterCycleWins >= 0) { "Master cycle wins cannot be negative" }
        require(bpBalance >= 0) { "BP balance cannot be negative" }
        require(this.errorKeys.none(String::isBlank)) { "Error keys cannot be blank" }
        require(!mechanicLocked || selectedMechanic != null) { "A locked mechanic selection cannot be absent" }
    }

    fun copy(
        entryContextId: UUID = this.entryContextId,
        revision: Long = this.revision,
        format: TowerBattleFormat = this.format,
        phase: TowerPlayPhase = this.phase,
        party: Collection<TowerPlayPartySlot> = this.party,
        selectedPokemonIds: Collection<UUID> = selectedPokemonOrder,
        rank: TowerRank = this.rank,
        rankPoints: Int = this.rankPoints,
        winsRequired: Int = this.winsRequired,
        masterCycleWins: Int = this.masterCycleWins,
        bpBalance: Long = this.bpBalance,
        errorKeys: Collection<String> = this.errorKeys,
        selectedMechanic: MajorBattleMechanic? = this.selectedMechanic,
        mechanicLocked: Boolean = this.mechanicLocked,
    ): TowerPlayViewState = TowerPlayViewState(
        entryContextId,
        revision,
        format,
        phase,
        party,
        selectedPokemonIds,
        rank,
        rankPoints,
        winsRequired,
        masterCycleWins,
        bpBalance,
        errorKeys,
        selectedMechanic,
        mechanicLocked,
    )

    override fun equals(other: Any?): Boolean =
        other is TowerPlayViewState &&
            entryContextId == other.entryContextId &&
            revision == other.revision &&
            format == other.format &&
            phase == other.phase &&
            party == other.party &&
            selectedPokemonOrder == other.selectedPokemonOrder &&
            rank == other.rank &&
            rankPoints == other.rankPoints &&
            winsRequired == other.winsRequired &&
            masterCycleWins == other.masterCycleWins &&
            bpBalance == other.bpBalance &&
            errorKeys == other.errorKeys &&
            selectedMechanic == other.selectedMechanic &&
            mechanicLocked == other.mechanicLocked

    override fun hashCode(): Int {
        var result = entryContextId.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + party.hashCode()
        result = 31 * result + selectedPokemonOrder.hashCode()
        result = 31 * result + rank.hashCode()
        result = 31 * result + rankPoints
        result = 31 * result + winsRequired
        result = 31 * result + masterCycleWins
        result = 31 * result + bpBalance.hashCode()
        result = 31 * result + errorKeys.hashCode()
        result = 31 * result + (selectedMechanic?.hashCode() ?: 0)
        result = 31 * result + mechanicLocked.hashCode()
        return result
    }

    override fun toString(): String =
        "TowerPlayViewState(entryContextId=$entryContextId, revision=$revision, format=$format, " +
            "phase=$phase, party=$party, selectedPokemonOrder=$selectedPokemonOrder, rank=$rank, " +
            "rankPoints=$rankPoints, winsRequired=$winsRequired, masterCycleWins=$masterCycleWins, " +
            "bpBalance=$bpBalance, errorKeys=$errorKeys, selectedMechanic=$selectedMechanic, " +
            "mechanicLocked=$mechanicLocked)"
}

internal sealed interface TowerPlayIntent {
    val requestId: UUID
    val entryContextId: UUID
    val expectedRevision: Long

    data class ToggleSelection(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
        val pokemonId: UUID,
    ) : TowerPlayIntent

    data class ChangeFormat(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
        val format: TowerBattleFormat,
    ) : TowerPlayIntent

    data class ChangeMechanic(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
        val mechanic: MajorBattleMechanic,
    ) : TowerPlayIntent

    data class LockTeam(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
    ) : TowerPlayIntent

    data class Start(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
    ) : TowerPlayIntent

    data class Resume(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
    ) : TowerPlayIntent

    data class Abandon(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
    ) : TowerPlayIntent
}

internal sealed interface TowerPlayMutationResult {
    val requestId: UUID

    data class Accepted(
        override val requestId: UUID,
        val state: TowerPlayViewState,
    ) : TowerPlayMutationResult

    class Rejected(
        override val requestId: UUID,
        val currentRevision: Long,
        val messageKey: String,
        fieldErrors: Map<String, String> = emptyMap(),
    ) : TowerPlayMutationResult {
        val fieldErrors: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(fieldErrors))

        init {
            require(currentRevision >= 0) { "Current revision cannot be negative" }
            require(messageKey.isNotBlank()) { "Message key cannot be blank" }
            require(this.fieldErrors.keys.none(String::isBlank)) { "Field error names cannot be blank" }
            require(this.fieldErrors.values.none(String::isBlank)) { "Field error keys cannot be blank" }
        }

        override fun equals(other: Any?): Boolean =
            other is Rejected && requestId == other.requestId && currentRevision == other.currentRevision &&
                messageKey == other.messageKey && fieldErrors == other.fieldErrors

        override fun hashCode(): Int {
            var result = requestId.hashCode()
            result = 31 * result + currentRevision.hashCode()
            result = 31 * result + messageKey.hashCode()
            return 31 * result + fieldErrors.hashCode()
        }

        override fun toString(): String =
            "Rejected(requestId=$requestId, currentRevision=$currentRevision, messageKey=$messageKey, " +
                "fieldErrors=$fieldErrors)"
    }
}

private fun <T> Collection<T>.immutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))

private fun <T> Collection<T>.immutableSet(): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(this))

private val PARTY_SLOT_RANGE = 0 until TOWER_REGISTERED_TEAM_SIZE
private val LEVEL_RANGE = 1..100
