package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/**
 * Leaf evaluation for the local recursive search.
 *
 * Values stay in board units: one point is one full HP bar and a living Pokemon is worth two
 * additional points. Stat stages are not assigned flat prices. Instead, public moves are
 * recalculated in the staged state so offensive and defensive changes are worth only the pressure
 * they can credibly create. Speed receives value only when it crosses a known action-order bound.
 */
internal object LocalLookaheadStateEvaluator {
    fun evaluate(state: BattleStateView, source: BattleDecisionContext): Double {
        val material = sideMaterial(state, BattleSide.ALLY) - sideMaterial(state, BattleSide.OPPONENT)
        if (battleEnded(state)) return material
        val pressure = attackPressure(state, BattleSide.ALLY, source) -
            attackPressure(state, BattleSide.OPPONENT, source)
        val speedControl = when (speedRelation(state)) {
            LocalPublicSpeedRelation.ALLY_FIRST -> SPEED_CONTROL_VALUE
            LocalPublicSpeedRelation.OPPONENT_FIRST -> -SPEED_CONTROL_VALUE
            LocalPublicSpeedRelation.AMBIGUOUS,
            LocalPublicSpeedRelation.UNAVAILABLE,
            -> 0.0
        }
        return material + pressure * FUTURE_PRESSURE_WEIGHT + speedControl
    }

    fun speedRelation(state: BattleStateView): LocalPublicSpeedRelation {
        val ally = activeSpeed(state, BattleSide.ALLY) ?: return LocalPublicSpeedRelation.UNAVAILABLE
        val opponent = activeSpeed(state, BattleSide.OPPONENT) ?: return LocalPublicSpeedRelation.UNAVAILABLE
        return when {
            ally.first > opponent.second -> LocalPublicSpeedRelation.ALLY_FIRST
            opponent.first > ally.second -> LocalPublicSpeedRelation.OPPONENT_FIRST
            else -> LocalPublicSpeedRelation.AMBIGUOUS
        }
    }

    fun switchOffensivePressureImprovement(
        candidate: BattleActionCandidate,
        source: BattleDecisionContext,
    ): Double? {
        if (candidate.kind != BattleActionKind.SWITCH || candidate.switchPokemonId == null) return null
        val current = attackPressure(source.state, BattleSide.ALLY, source)
        val switched = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY, candidate)
        val incomingActive = switched.pokemon.any {
            it.battlePokemonId == candidate.switchPokemonId && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        if (!incomingActive) return null
        return attackPressure(switched, BattleSide.ALLY, source) - current
    }

    fun switchInitiativeImprovement(
        candidate: BattleActionCandidate,
        source: BattleDecisionContext,
    ): Double? {
        if (candidate.kind != BattleActionKind.SWITCH || candidate.switchPokemonId == null) return null
        val switched = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY, candidate)
        val incomingActive = switched.pokemon.any {
            it.battlePokemonId == candidate.switchPokemonId && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        if (!incomingActive) return null
        return initiativeValue(speedRelation(switched)) - initiativeValue(speedRelation(source.state))
    }

    internal fun attackPressure(
        state: BattleStateView,
        side: BattleSide,
        source: BattleDecisionContext,
    ): Double = PublicFutureActionFactory.actions(state, side, source.publicActionCatalog)
        .asSequence()
        .filter { action ->
            action.kind == BattleActionKind.USE_MOVE &&
                action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
                (action.moveDetails?.power ?: 0.0) > 0.0
        }
        .map { action ->
            val calculatedContext = PublicBattleTacticalCalculator.calculate(
                actionContext(state, action, source),
                side,
            )
            val calculated = calculatedContext.candidates.single()
            val mechanics = LocalPublicMechanicsKernel.projectMove(calculated, calculatedContext, side)
            if (mechanics.publiclyNullified) return@map 0.0
            val facts = calculated.facts
            val accuracy = facts?.baseAccuracyProbability ?: 0.0
            val expectedDamage = facts?.standardDamageFractionRange?.let { range ->
                (range.minimum + range.maximum) / 2.0 * accuracy * mechanics.knownDamageMultiplier
            } ?: 0.0
            val knockoutProbability = facts?.standardDamageRollKoProbabilityRange?.let { range ->
                (range.minimum + range.maximum) / 2.0 * accuracy
            } ?: 0.0
            expectedDamage + knockoutProbability * FUTURE_KNOCKOUT_PRESSURE
        }
        .maxOrNull()
        ?: 0.0

    private fun sideMaterial(state: BattleStateView, side: BattleSide): Double {
        val knownLiving = state.pokemon.filter {
            it.side == side && !it.fainted && it.hpFraction > 0.0
        }
        val unseenLiving = (state.remainingPokemonBySide.getValue(side) - knownLiving.size).coerceAtLeast(0)
        return knownLiving.sumOf { pokemon ->
            pokemon.hpFraction + if (!pokemon.fainted && pokemon.hpFraction > 0.0) LIVING_POKEMON_VALUE else 0.0
        } + unseenLiving * (1.0 + LIVING_POKEMON_VALUE)
    }

    private fun initiativeValue(relation: LocalPublicSpeedRelation): Double = when (relation) {
        LocalPublicSpeedRelation.ALLY_FIRST -> 1.0
        LocalPublicSpeedRelation.OPPONENT_FIRST -> -1.0
        LocalPublicSpeedRelation.AMBIGUOUS,
        LocalPublicSpeedRelation.UNAVAILABLE,
        -> 0.0
    }

    private fun activeSpeed(state: BattleStateView, side: BattleSide): Pair<Int, Int>? {
        val pokemon = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val range = pokemon.combatStats?.speed ?: return null
        val stage = pokemon.statStages.entries.firstOrNull {
            canonicalStat(it.key) in SPEED_ALIASES
        }?.value?.coerceIn(-6, 6) ?: 0
        val statusMultiplier = if (canonicalId(pokemon.statusId) in PARALYSIS_IDS) 0.5 else 1.0
        val tailwindMultiplier = if (state.field.sideConditions.getValue(side).any {
                val remainingTurns = it.remainingTurns
                canonicalId(it.effectId) == "tailwind" && (remainingTurns == null || remainingTurns > 0)
            }
        ) {
            2.0
        } else {
            1.0
        }
        val multiplier = statusMultiplier * tailwindMultiplier
        return (applyStage(range.minimum, stage) * multiplier).toInt().coerceAtLeast(1) to
            (applyStage(range.maximum, stage) * multiplier).toInt().coerceAtLeast(1)
    }

    private fun actionContext(
        state: BattleStateView,
        action: BattleActionCandidate,
        source: BattleDecisionContext,
    ) = BattleDecisionContext(
        requestId = source.requestId,
        state = state,
        candidates = listOf(action),
        deadlineEpochMillis = source.deadlineEpochMillis,
        memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = source.publicActionCatalog,
    )

    private fun battleEnded(state: BattleStateView): Boolean = BattleSide.entries.any { side ->
        state.remainingPokemonBySide.getValue(side) <= 0
    }

    private fun applyStage(value: Int, stage: Int): Int = if (stage >= 0) {
        value * (2 + stage) / 2
    } else {
        value * 2 / (2 - stage)
    }.coerceAtLeast(1)

    private fun canonicalStat(stat: String): String = stat.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }
    private fun canonicalId(id: String?): String? = id?.substringAfter(':')?.lowercase()?.filter { it.isLetterOrDigit() }

    private const val LIVING_POKEMON_VALUE = 2.0
    private const val FUTURE_PRESSURE_WEIGHT = 0.30
    private const val FUTURE_KNOCKOUT_PRESSURE = 0.35
    private const val SPEED_CONTROL_VALUE = 0.15
    private val SPEED_ALIASES = setOf("speed", "spe")
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
}

internal enum class LocalPublicSpeedRelation { ALLY_FIRST, OPPONENT_FIRST, AMBIGUOUS, UNAVAILABLE }
