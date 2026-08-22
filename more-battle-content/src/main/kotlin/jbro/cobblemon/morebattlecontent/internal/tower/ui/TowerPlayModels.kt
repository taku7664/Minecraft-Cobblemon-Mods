package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgression
import jbro.cobblemon.morebattlecontent.internal.tower.TowerStreakStage
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_BOSS_INTERVAL
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
    val legendaryClass: Boolean = false,
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
    val currentWinStreak: Int,
    val bestWinStreak: Int,
    val bpBalance: Long,
    errorKeys: Collection<String>,
    val selectedMechanic: MajorBattleMechanic? = null,
    val mechanicLocked: Boolean = false,
    val legendaryClassAllowed: Boolean = false,
    val legendaryClassLocked: Boolean = false,
) {
    val party: List<TowerPlayPartySlot> = party.immutableList()
    val selectedPokemonOrder: List<UUID> = selectedPokemonIds.immutableList()
    val selectedPokemonIds: Set<UUID> = selectedPokemonOrder.immutableSet()
    val errorKeys: List<String> = errorKeys.immutableList()
    val streakStage: TowerStreakStage
        get() = TowerStreakStage.forNextBattle(currentWinStreak)
    val winsIntoSet: Int
        get() = currentWinStreak % TOWER_BOSS_INTERVAL
    val bpPerWin: Int
        get() = TowerProgression.rewardForNextVictory(
            TowerProgress(format, currentWinStreak, bestWinStreak),
        )

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
        require(currentWinStreak >= 0) { "Current win streak cannot be negative" }
        require(bestWinStreak >= currentWinStreak) { "Best win streak cannot be below current win streak" }
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
        currentWinStreak: Int = this.currentWinStreak,
        bestWinStreak: Int = this.bestWinStreak,
        bpBalance: Long = this.bpBalance,
        errorKeys: Collection<String> = this.errorKeys,
        selectedMechanic: MajorBattleMechanic? = this.selectedMechanic,
        mechanicLocked: Boolean = this.mechanicLocked,
        legendaryClassAllowed: Boolean = this.legendaryClassAllowed,
        legendaryClassLocked: Boolean = this.legendaryClassLocked,
    ): TowerPlayViewState = TowerPlayViewState(
        entryContextId,
        revision,
        format,
        phase,
        party,
        selectedPokemonIds,
        currentWinStreak,
        bestWinStreak,
        bpBalance,
        errorKeys,
        selectedMechanic,
        mechanicLocked,
        legendaryClassAllowed,
        legendaryClassLocked,
    )

    override fun equals(other: Any?): Boolean =
        other is TowerPlayViewState &&
            entryContextId == other.entryContextId &&
            revision == other.revision &&
            format == other.format &&
            phase == other.phase &&
            party == other.party &&
            selectedPokemonOrder == other.selectedPokemonOrder &&
            currentWinStreak == other.currentWinStreak &&
            bestWinStreak == other.bestWinStreak &&
            bpBalance == other.bpBalance &&
            errorKeys == other.errorKeys &&
            selectedMechanic == other.selectedMechanic &&
            mechanicLocked == other.mechanicLocked &&
            legendaryClassAllowed == other.legendaryClassAllowed &&
            legendaryClassLocked == other.legendaryClassLocked

    override fun hashCode(): Int {
        var result = entryContextId.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + party.hashCode()
        result = 31 * result + selectedPokemonOrder.hashCode()
        result = 31 * result + currentWinStreak
        result = 31 * result + bestWinStreak
        result = 31 * result + bpBalance.hashCode()
        result = 31 * result + errorKeys.hashCode()
        result = 31 * result + (selectedMechanic?.hashCode() ?: 0)
        result = 31 * result + mechanicLocked.hashCode()
        result = 31 * result + legendaryClassAllowed.hashCode()
        result = 31 * result + legendaryClassLocked.hashCode()
        return result
    }

    override fun toString(): String =
        "TowerPlayViewState(entryContextId=$entryContextId, revision=$revision, format=$format, " +
            "phase=$phase, party=$party, selectedPokemonOrder=$selectedPokemonOrder, streakStage=$streakStage, " +
            "currentWinStreak=$currentWinStreak, bestWinStreak=$bestWinStreak, winsIntoSet=$winsIntoSet, " +
            "bpPerWin=$bpPerWin, " +
            "bpBalance=$bpBalance, errorKeys=$errorKeys, selectedMechanic=$selectedMechanic, " +
            "mechanicLocked=$mechanicLocked, legendaryClassAllowed=$legendaryClassAllowed, " +
            "legendaryClassLocked=$legendaryClassLocked)"
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

    data class ChangeLegendaryClassAllowed(
        override val requestId: UUID,
        override val entryContextId: UUID,
        override val expectedRevision: Long,
        val allowed: Boolean,
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
