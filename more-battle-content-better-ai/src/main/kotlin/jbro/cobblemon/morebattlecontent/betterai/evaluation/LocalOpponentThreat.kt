package jbro.cobblemon.morebattlecontent.betterai.evaluation

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

/**
 * How dangerous each visible opponent is to the AI's living party, as a multiplier on its material.
 *
 * The weights express the AI's own priorities: knocking out or chipping a Pokemon that sweeps its
 * team is worth more than doing the same to a harmless one. They must never steer the model of what
 * the opponent chooses (see [materialAdjustment] and its callers), because the opponent protects
 * or exposes its Pokemon by its own interests, not by the AI's fears.
 *
 * Precision depends on the tier:
 * - Introductory: every opponent is 1.0.
 * - Standard: public types only, 0.8..1.3.
 * - Advanced: damage of its known and expected moves against each living ally, and speed, 0.6..1.7.
 * - Boss: Advanced, times how few allies can answer it and whether it can set up, 0.5..2.0.
 *
 * Weights are normalized to a mean of one so the scale of the evaluation does not move. Opponents
 * the AI has not seen, and any it cannot evaluate, stay at 1.0.
 *
 * Advanced and Boss singles also weigh the AI's own living Pokemon by their role: an ally that is
 * the only answer to a dangerous opponent is worth keeping, one with nothing left to answer is worth
 * less, so spending it to bring in a teammate safely becomes a real option. The ace judgement can be
 * wrong, so this band is narrower than the threat band and shrinks toward 1.0 by how much of the
 * opponent's team and moves the AI has actually seen. Ally entries share the same map, keyed by
 * their own battle Pokemon IDs.
 */
internal object LocalOpponentThreat {
    data class Band(val minimum: Double, val maximum: Double)

    fun band(tier: BattleTrainerTier): Band = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> Band(1.0, 1.0)
        BattleTrainerTier.STANDARD -> Band(0.8, 1.3)
        BattleTrainerTier.ADVANCED -> Band(0.6, 1.7)
        BattleTrainerTier.BOSS -> Band(0.5, 2.0)
    }

    fun weights(
        context: BattleDecisionContext,
        tier: BattleTrainerTier,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
    ): Map<UUID, Double> {
        if (tier == BattleTrainerTier.INTRODUCTORY) return emptyMap()
        val state = context.state
        val allies = living(state, BattleSide.ALLY)
        val foes = living(state, BattleSide.OPPONENT)
        if (allies.isEmpty() || foes.size < 2) return emptyMap()
        val damageBased = tier != BattleTrainerTier.STANDARD && state.format == BattleFormat.SINGLE
        val damage = if (damageBased) {
            damageThreats(context, allies, foes, tier == BattleTrainerTier.BOSS, cache, shouldContinue)
        } else null
        return if (damage != null) {
            val threats = normalize(
                damage.threats,
                band(tier),
                if (tier == BattleTrainerTier.BOSS) BOSS_RAW_MAXIMUM else ADVANCED_RAW_MAXIMUM,
            )
            threats + allyRoles(damage, roleBand(tier), roleConfidence(context))
        } else {
            normalize(typeThreats(allies, foes), band(tier), 1.0)
        }
    }

    /** Narrower than [band]: the AI may misjudge which of its Pokemon is the ace. */
    fun roleBand(tier: BattleTrainerTier): Band = when (tier) {
        BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD -> Band(1.0, 1.0)
        BattleTrainerTier.ADVANCED -> Band(0.9, 1.15)
        BattleTrainerTier.BOSS -> Band(0.85, 1.2)
    }

    /**
     * The weighted board minus the plain board, in material units: extra value the AI's evaluation
     * of its own action gives to opponents by threat. Zero whenever every weight is 1.0.
     */
    fun materialAdjustment(state: BattleStateView, weights: Map<UUID, Double>): Double {
        if (weights.isEmpty()) return 0.0
        return state.pokemon.sumOf { pokemon ->
            val weight = weights[pokemon.battlePokemonId] ?: return@sumOf 0.0
            if (pokemon.fainted || pokemon.hpFraction <= 0.0) return@sumOf 0.0
            val extra = (weight - 1.0) * (pokemon.hpFraction + LIVING_POKEMON_VALUE)
            if (pokemon.side == BattleSide.OPPONENT) -extra else extra
        }
    }

    /** Share of living allies that take super-effective damage from any of the foe's attacking types. */
    private fun typeThreats(
        allies: List<BattlePokemonStateView>,
        foes: List<BattlePokemonStateView>,
    ): Map<UUID, Double> = foes.associate { foe ->
        val attackingTypes = foe.knownBaseStabTypeIds.ifEmpty { foe.knownTypeIds }
        val exposed = allies.count { ally ->
            ally.knownTypeIds.isNotEmpty() && attackingTypes.any { type ->
                StandardTypeEffectiveness.multiplier(type, ally.knownTypeIds) >= 2.0
            }
        }
        foe.battlePokemonId to exposed.toDouble() / allies.size
    }

    /** Raw threat per foe, and which allies can answer each foe, from the same duels. */
    private class DamageTable(
        val threats: Map<UUID, Double>,
        val answersByFoe: Map<UUID, Set<UUID>>,
        val allyIds: List<UUID>,
    )

    private fun damageThreats(
        context: BattleDecisionContext,
        allies: List<BattlePokemonStateView>,
        foes: List<BattlePokemonStateView>,
        boss: Boolean,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): DamageTable? {
        val result = linkedMapOf<UUID, Double>()
        val answersByFoe = linkedMapOf<UUID, Set<UUID>>()
        for (foe in foes) {
            var attackScore = 0.0
            var evaluated = 0
            var answers = 0
            val answering = linkedSetOf<UUID>()
            for (ally in allies) {
                if (!shouldContinue()) return null
                val duel = duel(context, ally, foe, cache, shouldContinue) ?: continue
                evaluated++
                val incoming = duel.incoming / ally.hpFraction.coerceAtLeast(MINIMUM_HP)
                var score = when {
                    incoming >= 1.0 -> 1.0
                    incoming >= 0.5 -> 0.5
                    else -> 0.0
                }
                if (fasterCertain(foe, ally)) score *= FASTER_MULTIPLIER
                attackScore += score
                val hitsHard = duel.outgoing / foe.hpFraction.coerceAtLeast(MINIMUM_HP) >= 0.5
                if (hitsHard) answers++
                // A role needs the ally to land its hit: it survives one attack or surely moves first.
                if (hitsHard && (incoming < 1.0 || fasterCertain(ally, foe))) answering += ally.battlePokemonId
            }
            if (evaluated == 0) continue
            answersByFoe[foe.battlePokemonId] = answering
            var threat = attackScore / evaluated
            if (boss) {
                threat *= when (answers) {
                    0 -> NO_ANSWER_MULTIPLIER
                    1 -> ONE_ANSWER_MULTIPLIER
                    else -> 1.0
                }
                if (hasSetup(foe, context.publicActionCatalog)) threat *= SETUP_MULTIPLIER
            }
            result[foe.battlePokemonId] = threat
        }
        if (result.size < 2) return null
        return DamageTable(result, answersByFoe, allies.map { it.battlePokemonId })
    }

    /**
     * An ally's share of answering the opponent's threats: the sole answer to the most dangerous foe
     * has the largest role, an ally answering nothing has none. Mapped onto the band, normalized to a
     * mean of one, then pulled toward 1.0 by [confidence].
     */
    private fun allyRoles(table: DamageTable, band: Band, confidence: Double): Map<UUID, Double> =
        roleWeights(table.threats, table.answersByFoe, table.allyIds, band, confidence)

    internal fun roleWeights(
        threats: Map<UUID, Double>,
        answersByFoe: Map<UUID, Set<UUID>>,
        allyIds: List<UUID>,
        band: Band,
        confidence: Double,
    ): Map<UUID, Double> {
        if (band.minimum == band.maximum || confidence <= 0.0 || allyIds.size < 2) return emptyMap()
        val totalThreat = threats.values.sum()
        if (totalThreat <= 0.0) return emptyMap()
        val raw = allyIds.associateWith { allyId ->
            threats.entries.sumOf { (foeId, threat) ->
                val answering = answersByFoe[foeId].orEmpty()
                if (allyId in answering) threat / answering.size else 0.0
            } / totalThreat
        }
        return normalize(raw, band, 1.0).mapValues { (_, weight) -> 1.0 + (weight - 1.0) * confidence }
    }

    /**
     * How much of the opponent the role judgement rests on: the share of its remaining team seen,
     * half-credited until those Pokemon have shown moves.
     */
    private fun roleConfidence(context: BattleDecisionContext): Double {
        val state = context.state
        val seen = living(state, BattleSide.OPPONENT)
        val remaining = state.remainingPokemonBySide.getValue(BattleSide.OPPONENT).coerceAtLeast(seen.size)
        if (remaining == 0 || seen.isEmpty()) return 0.0
        val revealed = seen.count { it.knownMoveIds.isNotEmpty() }.toDouble() / seen.size
        return seen.size.toDouble() / remaining * (0.5 + 0.5 * revealed)
    }

    private data class Duel(val incoming: Double, val outgoing: Double)

    /** Both Pokemon placed in front of each other through public switch projection. */
    private fun duel(
        context: BattleDecisionContext,
        ally: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): Duel? {
        var position = context
        position = enter(position, ally, cache) ?: return null
        position = enter(position, foe, cache) ?: return null
        if (!shouldContinue()) return null
        val damageOnly = LocalDecisionTuning.CURRENT.copy(leafKnockoutPressure = 0.0)
        val incoming = LocalLookaheadStateEvaluator.attackPressure(
            position.state, BattleSide.OPPONENT, position, cache, shouldContinue, damageOnly,
            includeMoveHypotheses = true,
        )
        val outgoing = LocalLookaheadStateEvaluator.attackPressure(
            position.state, BattleSide.ALLY, position, cache, shouldContinue, damageOnly,
        )
        return Duel(incoming, outgoing)
    }

    private fun enter(
        source: BattleDecisionContext,
        pokemon: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): BattleDecisionContext? {
        val state = source.state
        if (state.pokemon.any { it.battlePokemonId == pokemon.battlePokemonId && it.activeSlot != null }) return source
        val action = PublicFutureActionFactory.actions(state, pokemon.side, source.publicActionCatalog)
            .firstOrNull { it.kind == BattleActionKind.SWITCH && it.switchPokemonId == pokemon.battlePokemonId }
            ?: return null
        val calculated = cache.getOrCalculate(state, pokemon.side, action, source.publicActionCatalog) {
            PublicBattleTacticalCalculator.calculate(source.copy(state = state, candidates = listOf(action)), pokemon.side)
        }
        val outgoingIds = state.pokemon.filter { it.side == pokemon.side && it.activeSlot == action.actorSlot }
            .mapTo(hashSetOf()) { it.battlePokemonId }
        val entered = LocalSwitchStateProjector.project(state, pokemon.side, calculated.candidates.single())
        return source.copy(state = entered, publicActionCatalog = source.publicActionCatalog.afterSwitch(outgoingIds))
    }

    private fun fasterCertain(foe: BattlePokemonStateView, ally: BattlePokemonStateView): Boolean {
        val foeSpeed = foe.combatStats?.speed ?: return false
        val allySpeed = ally.combatStats?.speed ?: return false
        return foeSpeed.minimum > allySpeed.maximum
    }

    private fun hasSetup(foe: BattlePokemonStateView, catalog: BattlePublicActionCatalogView): Boolean =
        catalog.inferredMovesForPokemon(foe.battlePokemonId)?.slots.orEmpty().any { slot ->
            slot.group == BattleOpponentMoveGroup.PURE_SETUP && slot.knowledge != BattleOpponentMoveKnowledge.GUESS
        } || catalog.forPokemon(foe.battlePokemonId).any { move ->
            move.details.damageCategory == BattleMoveDamageCategory.STATUS &&
                jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategories.isPureSelfSetup(move.details)
        }

    /**
     * Maps raw threat onto the band on a fixed scale, so two nearly equal threats stay nearly equal,
     * then normalizes to a mean of one and clamps back into the band.
     */
    private fun normalize(raw: Map<UUID, Double>, band: Band, rawMaximum: Double): Map<UUID, Double> {
        if (raw.size < 2 || band.minimum == band.maximum) return emptyMap()
        val mapped = raw.mapValues { (_, value) ->
            band.minimum + (band.maximum - band.minimum) * (value / rawMaximum).coerceIn(0.0, 1.0)
        }
        val mean = mapped.values.average()
        return mapped.mapValues { (_, value) -> (value / mean).coerceIn(band.minimum, band.maximum) }
    }

    private fun living(state: BattleStateView, side: BattleSide) =
        state.pokemon.filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }

    private const val LIVING_POKEMON_VALUE = 2.0
    private const val MINIMUM_HP = 0.05
    private const val FASTER_MULTIPLIER = 1.3
    private const val NO_ANSWER_MULTIPLIER = 1.5
    private const val ONE_ANSWER_MULTIPLIER = 1.2
    private const val SETUP_MULTIPLIER = 1.2
    private const val ADVANCED_RAW_MAXIMUM = FASTER_MULTIPLIER
    private const val BOSS_RAW_MAXIMUM = FASTER_MULTIPLIER * NO_ANSWER_MULTIPLIER * SETUP_MULTIPLIER
}
