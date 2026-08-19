package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import java.util.UUID

internal object FactoryProgression {
    private val roundIvs = intArrayOf(0, 4, 8, 12, 16, 20, 24, 31)

    fun roundForBattle(battleNumber: Int): Int {
        require(battleNumber > 0) { "Factory battle number must be positive" }
        return (battleNumber - 1) / BATTLES_PER_ROUND + 1
    }

    fun uniformIvForRound(round: Int): Int {
        require(round > 0) { "Factory round must be positive" }
        return roundIvs[(round - 1).coerceAtMost(roundIvs.lastIndex)]
    }

    fun strongerOfferCount(rentAndTradeCount: Int): Int {
        require(rentAndTradeCount >= 0) { "Factory rent and trade count must be non-negative" }
        return (rentAndTradeCount / TRADES_PER_ELEVATION).coerceAtMost(MAX_STRONGER_OFFERS)
    }

    fun poolWindow(levelMode: FactoryLevelMode, round: Int): FactoryPoolWindow {
        require(round > 0) { "Factory round must be positive" }
        return when (levelMode) {
            FactoryLevelMode.LEVEL_50 -> when (round) {
                1 -> FactoryPoolWindow(FactoryPoolGroup.STARTER, setOf(1))
                2 -> FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(1))
                3 -> FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(2))
                in 4..7 -> FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(round - 3))
                else -> FactoryPoolWindow(FactoryPoolGroup.ADVANCED, ALL_VARIANTS)
            }
            FactoryLevelMode.OPEN_LEVEL -> when (round) {
                in 1..4 -> FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(round))
                else -> FactoryPoolWindow(FactoryPoolGroup.ADVANCED, ALL_VARIANTS)
            }
        }
    }

    fun isFactoryHeadBattle(battleNumber: Int, format: FactoryBattleFormat): Boolean =
        format == FactoryBattleFormat.SINGLE && battleNumber in FACTORY_HEAD_BATTLES

    const val BATTLES_PER_ROUND = 7
    private const val TRADES_PER_ELEVATION = 7
    private const val MAX_STRONGER_OFFERS = 5
    private val ALL_VARIANTS = setOf(1, 2, 3, 4)
    private val FACTORY_HEAD_BATTLES = setOf(21, 49)
}

internal data class FactoryOpponentObservation(
    val speciesId: String,
    val revealedMoveIds: Set<String>,
    val revealedAbilityId: String?,
    val revealedHeldItemId: String?,
)

internal class FactorySwapOffer internal constructor(
    val token: UUID,
    val speciesId: String,
    revealedMoveIds: Set<String>,
    val revealedAbilityId: String?,
    val revealedHeldItemId: String?,
    val formId: String? = null,
) {
    val revealedMoveIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(revealedMoveIds))
}

internal enum class FactoryRunPhase {
    READY,
    IN_BATTLE,
    SWAP_DECISION,
    DRAFT_SELECTION,
    COMPLETE,
}

internal class FactoryRunSession(
    val runId: UUID,
    initialTeam: FactoryRentalTeam,
    val levelMode: FactoryLevelMode,
    private val healRentals: (FactoryRentalTeam) -> Unit,
    initialDraft: FactoryRentalDraft? = null,
    private val nextDraft: (FactoryLevelMode, round: Int, rentAndTradeCount: Int) -> FactoryRentalDraft? = { _, _, _ -> null },
) {
    var team: FactoryRentalTeam = initialTeam
        private set
    var wins: Int = 0
        private set
    var rentAndTradeCount: Int = 1
        private set
    var phase: FactoryRunPhase = FactoryRunPhase.READY
        private set
    var activeBattleId: UUID? = null
        private set
    var swapOffers: List<FactorySwapOffer> = emptyList()
        private set
    var pendingDraft: FactoryRentalDraft? = null
        private set
    private var revisableDraft: FactoryRentalDraft? = initialDraft
    val canReviseSelection: Boolean
        get() = phase == FactoryRunPhase.READY && activeBattleId == null && revisableDraft != null
    private var offeredSets: Map<UUID, FactoryRentalSet> = emptyMap()

    fun beginBattle(battleId: UUID) {
        check(phase == FactoryRunPhase.READY) { "Factory run is not ready for a battle" }
        check(activeBattleId == null) { "Factory run already has an active battle" }
        activeBattleId = battleId
        revisableDraft = null
        phase = FactoryRunPhase.IN_BATTLE
    }

    fun reorderTeam(orderedSetIds: List<String>): Boolean {
        if (phase != FactoryRunPhase.READY || activeBattleId != null) return false
        if (orderedSetIds.size != team.sets.size || orderedSetIds.toSet().size != team.sets.size) return false
        val setsById = team.sets.associateBy(FactoryRentalSet::setId)
        if (orderedSetIds.toSet() != setsById.keys) return false
        team = FactoryRentalTeam(team.format, orderedSetIds.map(setsById::getValue))
        return true
    }

    fun recordVictory(
        battleId: UUID,
        opponentSets: Map<UUID, FactoryRentalSet>,
        observations: Map<String, FactoryOpponentObservation>,
        beforeCommit: (winsAfter: Int) -> Unit = {},
    ): List<FactorySwapOffer> {
        requireActiveBattle(battleId, "victory")
        require(opponentSets.size == team.format.selectionSize) { "Opponent rental count does not match the Factory format" }
        FactoryRentalTeam(team.format, opponentSets.values.toList())
        require(observations.keys.all { observedId -> opponentSets.values.any { it.setId == observedId } }) {
            "Factory observations must reference an opponent rental"
        }

        val offers = opponentSets.map { (token, set) ->
            val observation = observations[set.setId]
            if (observation != null) {
                require(observation.speciesId == set.speciesId) { "Observed Factory species does not match the rental" }
                require(observation.revealedMoveIds.all { it in set.moveIds }) { "Observed move does not belong to the rental" }
                require(observation.revealedAbilityId == null || observation.revealedAbilityId == set.abilityId) {
                    "Observed ability does not belong to the rental"
                }
                require(observation.revealedHeldItemId == null || observation.revealedHeldItemId == set.heldItemId) {
                    "Observed held item does not belong to the rental"
                }
            }
            FactorySwapOffer(
                token = token,
                speciesId = set.speciesId,
                revealedMoveIds = observation?.revealedMoveIds ?: emptySet(),
                revealedAbilityId = observation?.revealedAbilityId,
                revealedHeldItemId = observation?.revealedHeldItemId,
                formId = set.formId,
            )
        }
        healRentals(team)
        val winsAfter = Math.addExact(wins, 1)
        beforeCommit(winsAfter)
        wins = winsAfter
        if (winsAfter % FactoryProgression.BATTLES_PER_ROUND == 0) {
            offeredSets = emptyMap()
            swapOffers = emptyList()
            activeBattleId = null
            pendingDraft = nextDraft(levelMode, FactoryProgression.roundForBattle(winsAfter + 1), rentAndTradeCount)
            phase = FactoryRunPhase.DRAFT_SELECTION
            return emptyList()
        }
        offeredSets = LinkedHashMap(opponentSets)
        swapOffers = Collections.unmodifiableList(ArrayList(offers))
        activeBattleId = null
        phase = FactoryRunPhase.SWAP_DECISION
        return offers
    }

    fun keepTeam() {
        check(phase == FactoryRunPhase.SWAP_DECISION) { "Factory run is not waiting for a swap decision" }
        finishSwapDecision()
    }

    fun swap(outgoingSetId: String, incomingToken: UUID) {
        check(phase == FactoryRunPhase.SWAP_DECISION) { "Factory run is not waiting for a swap decision" }
        require(team.sets.any { it.setId == outgoingSetId }) { "Outgoing rental is not in the current Factory team" }
        val incoming = requireNotNull(offeredSets[incomingToken]) { "Unknown Factory swap token" }
        val replacement = team.sets.filterNot { it.setId == outgoingSetId } + incoming
        team = FactoryRentalTeam(team.format, replacement)
        rentAndTradeCount++
        finishSwapDecision()
    }

    fun selectDraft(setIds: List<String>) {
        check(phase == FactoryRunPhase.DRAFT_SELECTION) { "Factory run is not waiting for a rental draft selection" }
        val draft = checkNotNull(pendingDraft) { "Factory rental draft is unavailable" }
        team = draft.select(setIds, team.format)
        revisableDraft = draft
        pendingDraft = null
        phase = FactoryRunPhase.READY
    }

    fun reviseSelection() {
        check(phase == FactoryRunPhase.READY) { "Factory run is not ready to revise rentals" }
        check(activeBattleId == null) { "Factory run already has an active battle" }
        pendingDraft = checkNotNull(revisableDraft) { "Factory rental draft is no longer revisable" }
        revisableDraft = null
        phase = FactoryRunPhase.DRAFT_SELECTION
    }

    fun recordLoss(
        battleId: UUID,
        beforeCommit: (completedWins: Int) -> Unit = {},
    ) {
        requireActiveBattle(battleId, "loss")
        beforeCommit(wins)
        activeBattleId = null
        phase = FactoryRunPhase.COMPLETE
        offeredSets = emptyMap()
        swapOffers = emptyList()
        pendingDraft = null
        revisableDraft = null
    }

    fun cancelBattle(battleId: UUID) {
        requireActiveBattle(battleId, "cancellation")
        activeBattleId = null
        phase = FactoryRunPhase.READY
        swapOffers = emptyList()
    }

    private fun requireActiveBattle(battleId: UUID, operation: String) {
        check(phase == FactoryRunPhase.IN_BATTLE) { "Factory $operation requires an active battle" }
        check(activeBattleId == battleId) { "Factory $operation does not match the active battle" }
    }

    private fun finishSwapDecision() {
        offeredSets = emptyMap()
        swapOffers = emptyList()
        phase = FactoryRunPhase.READY
    }
}
