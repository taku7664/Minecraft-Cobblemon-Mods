package jbro.cobblemon.morebattlecontent.betterai.simulation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
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
    private val publicTurnOffset: Int = 0,
    private val publicActionCatalog: BattlePublicActionCatalogView? = null,
) {
    val rulesFingerprint: String = worker.rulesFingerprint

    val root = NativeSearchPosition(
        frame = rootFrame,
        state = NativeBattleStateAdapter.adapt(rootFrame, publicTemplate, publicTurnOffset),
    )

    fun actions(
        position: NativeSearchPosition,
        side: BattleSide,
        maxVoluntarySwitchTargetsPerSlot: Int? = null,
    ): List<BattleActionCandidate> =
        NativeShowdownRequestActionFactory.actions(
            side, position.frame, maxVoluntarySwitchTargetsPerSlot, position.state, publicActionCatalog)

    /** Attack-only extension uses exact own move metadata; forced replacements remain untouched. */
    fun attackingActions(position: NativeSearchPosition): List<BattleActionCandidate> {
        val actions = actions(position, BattleSide.ALLY, 0)
        if (actions.isEmpty() || actions.all { it.kind == BattleActionKind.SWITCH || it.kind == BattleActionKind.WAIT }) {
            return actions
        }
        fun damaging(action: BattleActionCandidate): Boolean = when (action.kind) {
            BattleActionKind.USE_MOVE -> {
                val actor = position.state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == action.actorSlot
                }
                actor != null && publicActionCatalog?.forPokemon(actor.battlePokemonId)?.any {
                    it.moveId.substringAfter(':').equals(action.moveId?.substringAfter(':'), true) &&
                        it.details.damageCategory != BattleMoveDamageCategory.STATUS && it.details.power > 0.0
                } == true
            }
            BattleActionKind.COMPOSITE -> action.componentActions.all { component ->
                component.kind == BattleActionKind.WAIT || damaging(component)
            }
            else -> false
        }
        val attacks = actions.filter(::damaging)
        // A transformed or forced actor may have no catalogued damaging action. Do not turn the
        // position into an artificial terminal node when the constrained horizon cannot apply.
        return attacks.ifEmpty { actions }
    }

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
            state = NativeBattleStateAdapter.adapt(nextFrame, position.state, publicTurnOffset),
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
