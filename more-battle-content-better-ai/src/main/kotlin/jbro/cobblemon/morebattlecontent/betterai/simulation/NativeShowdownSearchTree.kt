package jbro.cobblemon.morebattlecontent.betterai.simulation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** A search node that keeps the native snapshot beside the state used for scoring. */
internal data class NativeSearchPosition(
    val frame: NativeBattleFrame,
    val state: BattleStateView,
    val recoilCredit: Double = 0.0,
)

/**
 * The only transition boundary for a native Showdown lookahead tree.
 *
 * Every child is produced from its parent's snapshot. The adapted [BattleStateView] is evaluation
 * material only and can never be used to reconstruct a later battle state.
 */
internal class NativeShowdownSearchTree(
    private val worker: NativeBranchWorker,
    rootFrame: NativeBattleFrame,
    publicTemplate: BattleStateView,
) {
    val rulesFingerprint: String = worker.rulesFingerprint

    val root = NativeSearchPosition(
        frame = rootFrame,
        state = NativeBattleStateAdapter.adapt(rootFrame, publicTemplate),
    )

    fun actions(position: NativeSearchPosition, side: BattleSide): List<BattleActionCandidate> =
        NativeShowdownRequestActionFactory.actions(side, position.frame)

    fun branch(
        position: NativeSearchPosition,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): NativeSearchPosition {
        require(!position.frame.ended) { "An ended native battle cannot produce another search branch" }
        val nextFrame = worker.branch(
            snapshotJson = position.frame.snapshotJson,
            p1Choice = NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, position.frame),
            p2Choice = NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, position.frame),
        )
        return NativeSearchPosition(
            frame = nextFrame,
            state = NativeBattleStateAdapter.adapt(nextFrame, position.state),
            // Material still charges the normal HP loss and self-KO. Refund half of the
            // move's recoil HP fraction, leaving 50 points per full HP bar.
            recoilCredit = position.recoilCredit +
                (nextFrame.recoilLossP1 - nextFrame.recoilLossP2) * RECOIL_REFUND,
        )
    }

    private companion object {
        const val RECOIL_REFUND = 0.5
    }
}
