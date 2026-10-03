package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** Actual callback state changes; these introduce no scoring constants. */
internal object LocalCallbackMoveStateProjector {
    fun takeHeart(state: BattleStateView, actorId: UUID): BattleStateView {
        val boosted = LocalStatStageChange.apply(state, actorId, actorId, mapOf("spa" to 1, "spd" to 1))
        return boosted.copyState(pokemon = boosted.pokemon.map {
            if (it.battlePokemonId == actorId) it.copyState(statusId = null) else it
        })
    }

    /** Meteor Beam and Electro Shot boost on their preparation turn, even when a Power Herb skips the wait. */
    fun prepareCharge(state: BattleStateView, actorId: UUID, moveId: String): BattleStateView {
        if (PublicIds.canonical(moveId) !in CHARGE_BOOST_MOVES) return state
        return LocalStatStageChange.apply(state, actorId, actorId, mapOf("spa" to 1))
    }

    private val CHARGE_BOOST_MOVES = setOf("meteorbeam", "electroshot")
}
