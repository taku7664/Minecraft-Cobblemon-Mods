package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/**
 * The public attack choices changed by a surviving active configuration, in the leaf's board units.
 * Both readings use the same post-turn HP, field, items and abilities. Ranks are restored to their
 * pre-turn values in both: the immediate scorer already owns newly changed rank pressure. Material,
 * speed, status, field, duel and persistent-rank values are not added a second time here.
 * This is a bounded pressure heuristic, not a forecast of unrevealed moves or a complete next turn.
 */
internal object LocalOnePlySwitchPositionValue {
    fun delta(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
        shouldContinue: () -> Boolean,
    ): Double {
        if (!tuning.onePlySwitchPosition || tuning.leafPressureWeight == 0.0) return 0.0
        val oldSlots = slots(before)
        val newSlots = slots(after)
        if (oldSlots == newSlots || oldSlots.keys != newSlots.keys) return 0.0
        val previous = before.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        val current = after.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        // A dead or missing former actor cannot occupy a counterfactual slot. KO/replacement value
        // stays with material and the separate forced-replacement search.
        if (oldSlots.values.any { current[it]?.let(::living) != true }) return 0.0
        // The next position has already restored a departing Transform user's original move pool.
        // Read that same future catalog in both frames, without changing the live source catalog.
        val catalog = source.publicActionCatalog.afterSwitch(oldSlots.values.toSet() - newSlots.values.toSet())
        val changedActors = oldSlots.keys.filter { oldSlots[it] != newSlots[it] }.flatMap {
            listOf(oldSlots.getValue(it), newSlots.getValue(it))
        }.toSet()
        for (id in changedActors) {
            val pokemon = current[id] ?: return 0.0
            // Unknown options or missing damage inputs cannot certify lost retaliation. The same
            // partial partner may remain in both frames; exhausted PP in a complete set is known.
            if (!catalog.isMoveSetComplete(id) || pokemon.combatStats == null || pokemon.level == null ||
                pokemon.knownTypeIds.isEmpty()) return 0.0
        }
        val rankedFrame = after.copyState(pokemon = after.pokemon.map { pokemon ->
            pokemon.copyState(statStages = previous[pokemon.battlePokemonId]?.statStages ?: pokemon.statStages)
        })
        val priorConfiguration = rankedFrame.copyState(pokemon = rankedFrame.pokemon.map { pokemon ->
            pokemon.copyState(activeSlot = previous[pokemon.battlePokemonId]?.activeSlot)
        })
        var complete = true
        val available = { if (!complete) false else shouldContinue().also { if (!it) complete = false } }
        fun pressure(state: BattleStateView): Double {
            val context = source.copy(state = state, publicActionCatalog = catalog)
            fun side(side: BattleSide) = LocalLookaheadStateEvaluator.attackPressure(state, side, context,
                cache, available, tuning, capDamageToRemainingHp = true)
            return side(BattleSide.ALLY) - side(BattleSide.OPPONENT)
        }
        val changed = pressure(rankedFrame) - pressure(priorConfiguration)
        // Never turn a budget-cut prefix into apparent positional evidence.
        return if (complete) changed * tuning.leafPressureWeight else 0.0
    }

    private fun slots(state: BattleStateView): Map<Pair<BattleSide, Int>, UUID> = state.pokemon
        .filter { it.activeSlot != null && living(it) }
        .associate { (it.side to requireNotNull(it.activeSlot)) to it.battlePokemonId }

    private fun living(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0
}
