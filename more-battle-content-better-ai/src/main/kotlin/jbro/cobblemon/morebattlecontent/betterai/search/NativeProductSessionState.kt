package jbro.cobblemon.morebattlecontent.betterai.search

import java.util.UUID
import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition

/** One posterior world and its exact reusable Showdown root at the current public decision point. */
internal data class NativeProductSessionWorld(
    val key: NativeSearchWorldKey,
    val probability: Double,
    val definition: NativeBattleDefinition,
    val rootSnapshot: NativeProductRootSnapshot,
    val publicContext: BattleDecisionContext,
    /** A command already submitted before an intermediate replacement request paused the turn. */
    val deferredAllyAction: BattleActionCandidate? = null,
    /** Opponent counterpart of [deferredAllyAction], retained until its delayed public evidence. */
    val deferredOpponentAction: BattleActionCandidate? = null,
) {
    init {
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
    }
}

/**
 * Battle-scoped native continuation handed from one product decision to the next.
 *
 * The pending own action is attached only after weighted product selection. The following public
 * decision can reconcile that action and the observed opponent response against each native root.
 */
internal data class NativeProductSessionState(
    val battleId: UUID,
    val format: BattleFormat,
    val rulesFingerprint: String,
    val worlds: List<NativeProductSessionWorld>,
    val publicTurn: Int,
    val lastObservedEventSequence: Long?,
    val pendingOwnAction: BattleActionCandidate? = null,
) {
    init {
        require(rulesFingerprint.isNotBlank())
        require(worlds.isNotEmpty())
        require(worlds.map(NativeProductSessionWorld::key).distinct().size == worlds.size)
        require(abs(worlds.sumOf(NativeProductSessionWorld::probability) - 1.0) <= NORMALIZATION_EPSILON)
        require(worlds.all { it.rootSnapshot.rulesFingerprint == rulesFingerprint })
        require(worlds.all {
            it.publicContext.state.battleId == battleId && it.publicContext.state.format == format
        })
        require(publicTurn >= 0)
        require(lastObservedEventSequence == null || lastObservedEventSequence >= 0L)
    }

    fun withPendingOwnAction(action: BattleActionCandidate): NativeProductSessionState =
        copy(pendingOwnAction = action)

    private companion object {
        const val NORMALIZATION_EPSILON = 1e-9
    }
}
