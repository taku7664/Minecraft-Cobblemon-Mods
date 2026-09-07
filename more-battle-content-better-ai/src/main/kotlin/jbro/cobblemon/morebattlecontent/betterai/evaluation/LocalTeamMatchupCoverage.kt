package jbro.cobblemon.morebattlecontent.betterai.evaluation

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

/**
 * Experimental coverage of public, living singles matchups, not a win probability.
 * Each target receives only its best available answer, so duplicate answers do not add points.
 * Damage is capped at the target's remaining HP; KO bonuses are excluded. Unseen Pokemon and
 * learnset candidates are not invented here. Partial revealed sets remain partial evidence.
 *
 * Virtual entries use public switch constraints, entry damage and entry abilities. They do not
 * model the intervening enemy turn, simultaneous entry order, or a complete multi-turn duel.
 * Status plans, move success conditions, speed and sharing finite PP across multiple foes need
 * separate validation; this bounded feature must not be interpreted as guaranteed counterplay.
 */
internal object LocalTeamMatchupCoverage {
    fun evaluate(
        state: BattleStateView,
        source: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
        tuning: LocalDecisionTuning,
    ): Double {
        if (state.format != BattleFormat.SINGLE) return 0.0
        val living = state.pokemon.filter { !it.fainted && it.hpFraction > 0.0 }
        if (BattleSide.entries.any { side -> living.count { it.side == side && it.activeSlot != null } != 1 }) return 0.0
        // Entry projection cannot invent a move catalog. Reject impossible-to-evaluate pairs before
        // allocating virtual switches, but retain original catalogs that a switch might restore.
        // Do not filter exhausted PP here: a known unusable attack is evidence, not missing data.
        val evidencedIds = (source.publicActionCatalog.entries + source.publicActionCatalog.originalEntries)
            .filter { entry -> entry.moves.any { isDamagingTemplate(it) } }
            .mapTo(hashSetOf()) { it.battlePokemonId }
        val allies = living.filter { it.side == BattleSide.ALLY && it.battlePokemonId in evidencedIds }
        val foes = living.filter { it.side == BattleSide.OPPONENT && it.battlePokemonId in evidencedIds }
        if (allies.isEmpty() || foes.isEmpty()) return 0.0
        val ownAnswers = mutableMapOf<UUID, Double>()
        val enemyAnswers = mutableMapOf<UUID, Double>()
        var complete = true
        val available = {
            if (!complete) false else shouldContinue().also { if (!it) complete = false }
        }
        val damageOnly = tuning.copy(leafKnockoutPressure = 0.0)
        for (ally in allies) for (foe in foes) {
            if (!available()) return 0.0
            var position = sourceAt(source, state, source.publicActionCatalog)
            position = enter(position, ally, cache, available) ?: continue
            position = enter(position, foe, cache, available) ?: continue
            val actors = listOf(ally, foe).map { original ->
                position.state.pokemon.single { it.battlePokemonId == original.battlePokemonId }
            }
            if (actors.any { it.fainted || it.hpFraction <= 0.0 || it.knownTypeIds.isEmpty() || it.combatStats == null }) continue
            // No known damaging template is missing evidence, not evidence of zero retaliation.
            // An exhausted known move is different: it stays known but produces no attack action.
            if (actors.any { actor -> position.publicActionCatalog.forPokemon(actor.battlePokemonId)
                    .none { isDamagingTemplate(it) } }) continue
            val outgoing = LocalLookaheadStateEvaluator.attackPressure(position.state, BattleSide.ALLY,
                position, cache, available, damageOnly)
            val incoming = LocalLookaheadStateEvaluator.attackPressure(position.state, BattleSide.OPPONENT,
                position, cache, available, damageOnly)
            if (!complete) return 0.0 // Never turn a budget-truncated prefix into apparent coverage.
            val advantage = (outgoing / actors[1].hpFraction).coerceIn(0.0, 1.0) -
                (incoming / actors[0].hpFraction).coerceIn(0.0, 1.0)
            ownAnswers[foe.battlePokemonId] = maxOf(ownAnswers[foe.battlePokemonId] ?: 0.0, advantage)
            enemyAnswers[ally.battlePokemonId] = maxOf(enemyAnswers[ally.battlePokemonId] ?: 0.0, -advantage)
        }
        if (!complete) return 0.0
        // Average over evidenced targets, not a flat bonus per surviving team member.
        return ownAnswers.values.averageOrZero() - enemyAnswers.values.averageOrZero()
    }

    private fun enter(
        source: BattleDecisionContext,
        pokemon: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
        available: () -> Boolean,
    ): BattleDecisionContext? {
        val state = source.state
        if (state.pokemon.any { it.battlePokemonId == pokemon.battlePokemonId && it.activeSlot != null }) return source
        if (!available()) return null
        val action = PublicFutureActionFactory.actions(state, pokemon.side, source.publicActionCatalog)
            .firstOrNull { it.kind == BattleActionKind.SWITCH && it.switchPokemonId == pokemon.battlePokemonId }
            ?: return null
        val calculated = cache.getOrCalculate(state, pokemon.side, action, source.publicActionCatalog) {
            PublicBattleTacticalCalculator.calculate(BattleDecisionContext(source.requestId, state,
                listOf(action), source.deadlineEpochMillis, memory = source.memory,
                publicActionCatalog = source.publicActionCatalog), pokemon.side)
        }
        val outgoingIds = state.pokemon.filter { it.side == pokemon.side && it.activeSlot == action.actorSlot }
            .mapTo(hashSetOf()) { it.battlePokemonId }
        val entered = LocalSwitchStateProjector.project(state, pokemon.side, calculated.candidates.single())
        return sourceAt(source, entered, source.publicActionCatalog.afterSwitch(outgoingIds))
    }

    private fun sourceAt(source: BattleDecisionContext, state: BattleStateView, catalog: BattlePublicActionCatalogView) =
        BattleDecisionContext(source.requestId, state, source.candidates, source.deadlineEpochMillis,
            memory = source.memory, publicActionCatalog = catalog)

    private fun Collection<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun isDamagingTemplate(move: BattlePublicMoveOptionView): Boolean =
        move.details.damageCategory != BattleMoveDamageCategory.STATUS && (move.details.power ?: 0.0) > 0.0
}
