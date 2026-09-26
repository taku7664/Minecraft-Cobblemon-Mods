package jbro.cobblemon.morebattlecontent.betterai.evaluation

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState

internal data class LocalStatStageMarginalValue(
    /** Realizable outgoing minus incoming attack-pressure change, in HP-bar units. */
    val pressureBoardDelta: Double,
    /** Public action-order probability change, in board units. */
    val speedBoardDelta: Double,
    /** Whether public moves or public Speed ranges were sufficient to price every changed stat. */
    val publiclyResolved: Boolean,
) {
    /** Position value in the same units as one HP bar. */
    val boardDelta: Double = pressureBoardDelta + speedBoardDelta
    val score: Double = boardDelta * SCORE_PER_BOARD_POINT

    private companion object {
        const val SCORE_PER_BOARD_POINT = 100.0
    }
}

/**
 * Prices stat stages by the public outcome they can actually change.
 *
 * There is deliberately no preferred raw stat such as 120 and no permanent price per stage. A
 * stage is worth the extra HP/KO pressure it creates, the incoming pressure it prevents, and the
 * public probability of crossing the action-order boundary. Damage is capped at current HP by the
 * shared lookahead pressure calculator, so an already-secured knockout gains no setup value.
 */
internal object LocalStatStageMarginalEvaluator {
    fun evaluate(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalStatStageMarginalValue {
        val changes = stageChanges(before, after)
        if (changes.isEmpty()) return LocalStatStageMarginalValue(0.0, 0.0, true)

        val beforePressure = positionAttackPressure(before, source, calculationCache, tuning, shouldContinue)
        val afterPressure = positionAttackPressure(after, source, calculationCache, tuning, shouldContinue)
        val beforeSpeed = speedPositionValue(before, tuning)
        val afterSpeed = speedPositionValue(after, tuning)
        val speedDelta = if (beforeSpeed != null && afterSpeed != null) afterSpeed - beforeSpeed else 0.0
        return LocalStatStageMarginalValue(
            pressureBoardDelta = afterPressure - beforePressure,
            speedBoardDelta = speedDelta,
            publiclyResolved = changes.all { change ->
                publicInputsResolve(change, before, source) ||
                    (change.stat == SPEED && beforeSpeed != null && afterSpeed != null)
            },
        )
    }

    /**
     * Root-action value. Exact public marginal value replaces the old flat setup score. When the
     * required move catalog or Speed range is absent, a small signed fallback keeps an unknown but
     * legal setup action alive without pretending that every stage is equally valuable.
     */
    fun candidateScore(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double? {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.statStages.isNotEmpty()
        }
        if (effects.isEmpty()) return null
        var state = context.state
        var score = 0.0
        var appliedAny = false
        effects.forEach { effect ->
            val targetIds = effectTargets(candidate, state, effect.target)
            if (targetIds.isEmpty()) return@forEach
            val applied = applyStages(state, targetIds, effect.statStages)
            appliedAny = true
            val resolvedScore = scoreWithUnknownFallback(
                state,
                applied,
                context,
                calculationCache,
                tuning,
            )
            val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
            score += resolvedScore * probability
            if (probability >= CERTAIN_PROBABILITY) state = applied
        }
        return if (appliedAny) score * accuracy.coerceIn(0.0, 1.0) else null
    }

    /**
     * Keeps exact outcome value for every changed stat whose public inputs are available, and uses
     * the small signed reserve only for the remainder. Treating a mixed effect as all-known or
     * all-unknown made one unrevealed axis erase useful public damage information for every other
     * axis on the same move.
     */
    private fun scoreWithUnknownFallback(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
    ): Double {
        val changes = stageChanges(before, after)
        if (changes.isEmpty()) return 0.0
        val speedResolved = speedPositionValue(before, tuning) != null && speedPositionValue(after, tuning) != null
        val (resolved, unresolved) = changes.partition { change ->
            publicInputsResolve(change, before, source) || change.stat == SPEED && speedResolved
        }
        if (unresolved.isEmpty()) {
            return evaluate(before, after, source, calculationCache, tuning).score
        }
        val resolvedScore = if (resolved.isEmpty()) {
            0.0
        } else {
            evaluate(
                before,
                applyStageChanges(before, resolved),
                source,
                calculationCache,
                tuning,
            ).score
        }
        val fallback = fallbackScore(before, applyStageChanges(before, unresolved))
        // An unknown opposing physical set does not make our own Body Press unknowable.
        // Preserve that calculable offensive part while the fallback covers only the
        // unresolved defensive part of the same Defense stage change.
        val bodyPressScore = unresolved.filter { it.stat == DEFENSE }.sumOf { change ->
            val attacker = before.pokemon.firstOrNull { it.battlePokemonId == change.pokemonId }
            val hasBodyPress = source.publicActionCatalog.forPokemon(change.pokemonId).any {
                canonical(it.moveId) == "bodypress"
            }
            if (!hasBodyPress || !attacker.hasPublicCombatStats() ||
                active(before, opposite(change.side)).none { it.hasPublicCombatStats() }
            ) {
                0.0
            } else {
                val changed = applyStageChanges(before, listOf(change))
                val beforePressure = LocalLookaheadStateEvaluator.attackPressure(
                    before, change.side, source, calculationCache, tuning = tuning,
                    capDamageToRemainingHp = true,
                )
                val afterPressure = LocalLookaheadStateEvaluator.attackPressure(
                    changed, change.side, source, calculationCache, tuning = tuning,
                    capDamageToRemainingHp = true,
                )
                (afterPressure - beforePressure) *
                    (if (change.side == BattleSide.ALLY) 1.0 else -1.0) * SCORE_PER_BOARD_POINT
            }
        }
        return resolvedScore + fallback + bodyPressScore
    }

    /** Values only rank changes while preserving the actual post-turn HP, field and active slots. */
    fun transitionValue(
        beforeTurn: BattleStateView,
        afterTurn: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalStatStageMarginalValue {
        val beforeById = beforeTurn.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        val postTurnWithoutStageChanges = afterTurn.copyState(
            pokemon = afterTurn.pokemon.map { pokemon ->
                val oldStages = beforeById[pokemon.battlePokemonId]?.statStages ?: pokemon.statStages
                pokemon.copyState(statStages = oldStages)
            },
        )
        return evaluate(
            postTurnWithoutStageChanges,
            afterTurn,
            source,
            calculationCache,
            tuning,
            shouldContinue,
        )
    }

    fun speedTransitionBoardDelta(
        before: BattleStateView,
        after: BattleStateView,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val beforeSpeed = speedPositionValue(before, tuning)
        val afterSpeed = speedPositionValue(after, tuning)
        return if (beforeSpeed != null && afterSpeed != null) afterSpeed - beforeSpeed else 0.0
    }

    private fun positionAttackPressure(
        state: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
        tuning: LocalDecisionTuning,
        shouldContinue: () -> Boolean,
    ): Double = LocalLookaheadStateEvaluator.attackPressure(
        state,
        BattleSide.ALLY,
        source,
        calculationCache,
        shouldContinue,
        tuning,
        capDamageToRemainingHp = true,
    ) - LocalLookaheadStateEvaluator.attackPressure(
        state,
        BattleSide.OPPONENT,
        source,
        calculationCache,
        shouldContinue,
        tuning,
        capDamageToRemainingHp = true,
    )

    private fun speedPositionValue(state: BattleStateView, tuning: LocalDecisionTuning): Double? {
        val allies = active(state, BattleSide.ALLY)
        val opponents = active(state, BattleSide.OPPONENT)
        if (allies.isEmpty() || opponents.isEmpty()) return null
        val probabilities = allies.flatMap { ally ->
            opponents.map { opponent ->
                LocalPublicTurnOrder.speedOrderProbability(state, ally, opponent) ?: return null
            }
        }
        val allyFirstProbability = probabilities.average()
        return (allyFirstProbability * 2.0 - 1.0) * tuning.leafSpeedControlValue
    }

    private fun publicInputsResolve(
        change: StageChange,
        state: BattleStateView,
        source: BattleDecisionContext,
    ): Boolean {
        val ownCatalog = source.publicActionCatalog.forPokemon(change.pokemonId)
        val damagingOwnMoves = ownCatalog.filter { it.details.damageCategory != BattleMoveDamageCategory.STATUS }
        return when (change.stat) {
            ATTACK -> outgoingMovesResolve(
                change,
                state,
                source,
                damagingOwnMoves.any { it.details.damageCategory == BattleMoveDamageCategory.PHYSICAL },
            )
            SPECIAL_ATTACK -> outgoingMovesResolve(
                change,
                state,
                source,
                damagingOwnMoves.any { it.details.damageCategory == BattleMoveDamageCategory.SPECIAL },
            )
            ACCURACY -> outgoingMovesResolve(change, state, source, damagingOwnMoves.isNotEmpty())
            DEFENSE -> incomingMovesResolve(change, BattleMoveDamageCategory.PHYSICAL, state, source)
            SPECIAL_DEFENSE -> incomingMovesResolve(change, BattleMoveDamageCategory.SPECIAL, state, source)
            EVASION -> incomingMovesResolve(change, null, state, source)
            SPEED -> false
            else -> false
        }
    }

    private fun outgoingMovesResolve(
        change: StageChange,
        state: BattleStateView,
        source: BattleDecisionContext,
        relevantMoveExists: Boolean,
    ): Boolean {
        if (!relevantMoveExists) return source.publicActionCatalog.isMoveSetComplete(change.pokemonId)
        val attacker = state.pokemon.firstOrNull { it.battlePokemonId == change.pokemonId }
        val defenders = active(state, opposite(change.side))
        return attacker.hasPublicCombatStats() && defenders.any { it.hasPublicCombatStats() }
    }

    private fun incomingMovesResolve(
        change: StageChange,
        category: BattleMoveDamageCategory?,
        state: BattleStateView,
        source: BattleDecisionContext,
    ): Boolean {
        val attackers = active(state, opposite(change.side))
        if (attackers.isEmpty()) return true
        val targetHasStats = state.pokemon.firstOrNull {
            it.battlePokemonId == change.pokemonId
        }.hasPublicCombatStats()
        if (!targetHasStats) return false
        return attackers.all { attacker ->
            val relevantMoveExists = source.publicActionCatalog.forPokemon(attacker.battlePokemonId).any { option ->
                option.details.damageCategory != BattleMoveDamageCategory.STATUS &&
                    (category == null || option.details.damageCategory == category)
            }
            if (relevantMoveExists) {
                attacker.hasPublicCombatStats()
            } else {
                source.publicActionCatalog.isMoveSetComplete(attacker.battlePokemonId)
            }
        }
    }

    private fun BattlePokemonStateView?.hasPublicCombatStats(): Boolean =
        this?.level != null && this.combatStats != null

    private fun fallbackScore(before: BattleStateView, after: BattleStateView): Double {
        val trickRoom = before.field.roomEffects.any {
            val remainingTurns = it.remainingTurns
            canonical(it.effectId) == "trickroom" && (remainingTurns == null || remainingTurns > 0)
        }
        return stageChanges(before, after).sumOf { change ->
            val perspective = if (change.side == BattleSide.ALLY) 1.0 else -1.0
            val fieldDirection = if (change.stat == SPEED && trickRoom) -1.0 else 1.0
            change.delta * perspective * fieldDirection * FALLBACK_SCORE_PER_STAGE
        }
    }

    private fun effectTargets(
        candidate: BattleActionCandidate,
        state: BattleStateView,
        target: BattleMoveEffectTarget,
    ): Set<UUID> = when (target) {
        BattleMoveEffectTarget.USER -> candidate.actorSlot?.let { slot ->
            active(state, BattleSide.ALLY).singleOrNull { it.activeSlot == slot }?.battlePokemonId
        }?.let(::setOf).orEmpty()
        BattleMoveEffectTarget.SELECTED_TARGET -> candidate.targets.mapNotNullTo(linkedSetOf()) { slot ->
            state.pokemon.singleOrNull {
                it.side == slot.side && it.activeSlot == slot.slot && !it.fainted && it.hpFraction > 0.0
            }?.battlePokemonId
        }
        BattleMoveEffectTarget.USER_SIDE,
        BattleMoveEffectTarget.TARGET_SIDE,
        BattleMoveEffectTarget.FIELD,
        -> emptySet()
    }

    private fun applyStages(
        state: BattleStateView,
        targetIds: Set<UUID>,
        changes: Map<String, Int>,
    ): BattleStateView = state.copyState(
        pokemon = state.pokemon.map { pokemon ->
            if (pokemon.battlePokemonId !in targetIds || pokemon.fainted || pokemon.hpFraction <= 0.0) {
                pokemon
            } else {
                val stages = canonicalStages(pokemon.statStages).toMutableMap()
                changes.forEach { (stat, amount) ->
                    val id = canonical(stat)
                    stages[id] = ((stages[id] ?: 0) + amount).coerceIn(-6, 6)
                }
                pokemon.copyState(statStages = stages)
            }
        },
    )

    private fun applyStageChanges(
        state: BattleStateView,
        changes: List<StageChange>,
    ): BattleStateView =
        changes.groupBy(StageChange::pokemonId).entries.fold(state) { projected, (pokemonId, pokemonChanges) ->
            applyStages(
                projected,
                setOf(pokemonId),
                pokemonChanges.associate { change -> change.stat to change.delta },
            )
        }

    private fun stageChanges(before: BattleStateView, after: BattleStateView): List<StageChange> {
        val beforeById = before.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        return after.pokemon.flatMap { pokemon ->
            val previous = beforeById[pokemon.battlePokemonId] ?: return@flatMap emptyList()
            val oldStages = canonicalStages(previous.statStages)
            val newStages = canonicalStages(pokemon.statStages)
            (oldStages.keys + newStages.keys).mapNotNull { stat ->
                val delta = (newStages[stat] ?: 0) - (oldStages[stat] ?: 0)
                if (delta == 0) null else StageChange(pokemon.battlePokemonId, pokemon.side, stat, delta)
            }
        }
    }

    private fun canonicalStages(stages: Map<String, Int>): Map<String, Int> = stages.entries.associate { (stat, value) ->
        canonical(stat) to value.coerceIn(-6, 6)
    }

    private fun active(state: BattleStateView, side: BattleSide): List<BattlePokemonStateView> = state.pokemon.filter {
        it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
    }

    private fun opposite(side: BattleSide): BattleSide =
        if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private fun canonical(value: String?): String = value.orEmpty()
        .substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)
        .let { STAT_ALIASES[it] ?: it }

    private data class StageChange(
        val pokemonId: UUID,
        val side: BattleSide,
        val stat: String,
        val delta: Int,
    )

    private const val CERTAIN_PROBABILITY = 0.999_999
    private const val FALLBACK_SCORE_PER_STAGE = 4.0
    private const val SCORE_PER_BOARD_POINT = 100.0
    private const val ATTACK = "attack"
    private const val DEFENSE = "defense"
    private const val SPECIAL_ATTACK = "specialattack"
    private const val SPECIAL_DEFENSE = "specialdefense"
    private const val SPEED = "speed"
    private const val ACCURACY = "accuracy"
    private const val EVASION = "evasion"
    private val STAT_ALIASES = mapOf(
        "atk" to ATTACK,
        "def" to DEFENSE,
        "defence" to DEFENSE,
        "spa" to SPECIAL_ATTACK,
        "spatk" to SPECIAL_ATTACK,
        "specialatk" to SPECIAL_ATTACK,
        "spd" to SPECIAL_DEFENSE,
        "spdef" to SPECIAL_DEFENSE,
        "specialdefence" to SPECIAL_DEFENSE,
        "spe" to SPEED,
        "acc" to ACCURACY,
        "eva" to EVASION,
    )
}
