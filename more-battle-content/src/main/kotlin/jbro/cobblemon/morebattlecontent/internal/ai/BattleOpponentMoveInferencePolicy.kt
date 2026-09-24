package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

/**
 * Balance knobs for the four inference stages. Slot construction reads this policy; it does not
 * branch on a tier anywhere else.
 */
internal data class BattleOpponentMoveInferencePolicy(
    val expectedStabSlots: Int,
    val expectedCoverageSlots: Int,
    val confirmedHiddenStabSlots: Int,
    val confirmedHiddenSetupSlots: Int,
    val guessedStatusSlots: Int,
    val preserveActualAttackStatusCounts: Boolean,
) {
    val readsHiddenSet: Boolean
        get() = confirmedHiddenStabSlots > 0 || confirmedHiddenSetupSlots > 0 ||
            preserveActualAttackStatusCounts

    init {
        listOf(
            expectedStabSlots,
            expectedCoverageSlots,
            confirmedHiddenStabSlots,
            confirmedHiddenSetupSlots,
            guessedStatusSlots,
        ).forEach { require(it in 0..MAX_MOVE_SLOTS) }
    }

    private companion object {
        const val MAX_MOVE_SLOTS = 4
    }
}

internal object BattleOpponentMoveInferencePolicies {
    private val byTier = mapOf(
        BattleTrainerTier.INTRODUCTORY to BattleOpponentMoveInferencePolicy(
            expectedStabSlots = 1,
            expectedCoverageSlots = 0,
            confirmedHiddenStabSlots = 0,
            confirmedHiddenSetupSlots = 0,
            guessedStatusSlots = 0,
            preserveActualAttackStatusCounts = false,
        ),
        BattleTrainerTier.STANDARD to BattleOpponentMoveInferencePolicy(
            expectedStabSlots = 1,
            expectedCoverageSlots = 1,
            confirmedHiddenStabSlots = 0,
            confirmedHiddenSetupSlots = 0,
            guessedStatusSlots = 1,
            preserveActualAttackStatusCounts = false,
        ),
        BattleTrainerTier.ADVANCED to BattleOpponentMoveInferencePolicy(
            expectedStabSlots = 0,
            expectedCoverageSlots = 4,
            confirmedHiddenStabSlots = 1,
            confirmedHiddenSetupSlots = 0,
            guessedStatusSlots = 0,
            preserveActualAttackStatusCounts = true,
        ),
        BattleTrainerTier.BOSS to BattleOpponentMoveInferencePolicy(
            expectedStabSlots = 0,
            expectedCoverageSlots = 4,
            confirmedHiddenStabSlots = 2,
            confirmedHiddenSetupSlots = 1,
            guessedStatusSlots = 0,
            preserveActualAttackStatusCounts = true,
        ),
    )

    fun forTier(tier: BattleTrainerTier): BattleOpponentMoveInferencePolicy = requireNotNull(byTier[tier])
}
