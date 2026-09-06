package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

internal data class LocalImmediateTurnScore(
    val materialDelta: Double,
    val stageDelta: Double,
    val statusDelta: Double,
    val speedControlDelta: Double,
    val fieldDelta: Double,
) {
    val total: Double = materialDelta + stageDelta + statusDelta + speedControlDelta + fieldDelta
}

/**
 * Scores only what changed during one projected turn.
 *
 * Damage keeps separate KO/survival states with a representative roll within each class.
 * Removal value is already part of materialDelta; secondary effects can still use weighted score.
 */
internal object LocalImmediateTurnScorer {
    fun score(before: BattleStateView, after: BattleStateView): LocalImmediateTurnScore {
        val beforeMaterial = LocalBoardMaterial.evaluate(before)
        val afterMaterial = LocalBoardMaterial.evaluate(after)
        val beforeStages = positionStages(before)
        val afterStages = positionStages(after)
        val beforeStatus = positionStatus(before)
        val afterStatus = positionStatus(after)
        val beforeSpeed = speedControlValue(before)
        val afterSpeed = speedControlValue(after)
        val beforeField = positionField(before)
        val afterField = positionField(after)
        return LocalImmediateTurnScore(
            materialDelta = afterMaterial - beforeMaterial,
            stageDelta = afterStages - beforeStages,
            statusDelta = afterStatus - beforeStatus,
            speedControlDelta = afterSpeed - beforeSpeed,
            fieldDelta = afterField - beforeField,
        )
    }

    fun expectedEffectScore(
        before: BattleStateView,
        afterEffect: BattleStateView,
        probability: Double,
    ): Double = score(before, afterEffect).total * probability.coerceIn(0.0, 1.0)

    private fun positionStages(state: BattleStateView): Double =
        sideStages(state, BattleSide.ALLY) - sideStages(state, BattleSide.OPPONENT)

    private fun sideStages(state: BattleStateView, side: BattleSide): Double = state.pokemon
        .asSequence()
        .filter { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        .sumOf(::stageValue)
        .coerceIn(-MAX_STAGE_VALUE, MAX_STAGE_VALUE)

    private fun stageValue(pokemon: BattlePokemonStateView): Double = pokemon.statStages.entries.sumOf { (stat, stage) ->
        stage.coerceIn(-6, 6) * (STAGE_WEIGHTS[canonicalId(stat)] ?: DEFAULT_STAGE_WEIGHT)
    }

    private fun positionStatus(state: BattleStateView): Double =
        sideStatusBurden(state, BattleSide.OPPONENT) - sideStatusBurden(state, BattleSide.ALLY)

    private fun sideStatusBurden(state: BattleStateView, side: BattleSide): Double = state.pokemon
        .asSequence()
        .filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        .sumOf { statusBurden(it.statusId) }

    private fun statusBurden(statusId: String?): Double = when (canonicalId(statusId)) {
        null -> 0.0
        "tox", "toxic", "badlypoisoned" -> 0.35
        "slp", "sleep", "frz", "freeze", "frozen" -> 0.35
        "par", "paralysis", "paralyzed", "paralysed", "brn", "burn", "burned", "burnt" -> 0.25
        "psn", "poison", "poisoned" -> 0.20
        else -> 0.15
    }

    private fun speedControlValue(state: BattleStateView): Double = when (LocalLookaheadStateEvaluator.speedRelation(state)) {
        LocalPublicSpeedRelation.ALLY_FIRST -> SPEED_CONTROL_VALUE
        LocalPublicSpeedRelation.OPPONENT_FIRST -> -SPEED_CONTROL_VALUE
        LocalPublicSpeedRelation.AMBIGUOUS,
        LocalPublicSpeedRelation.UNAVAILABLE,
        -> 0.0
    }

    private fun positionField(state: BattleStateView): Double =
        sideFieldValue(state, BattleSide.ALLY) - sideFieldValue(state, BattleSide.OPPONENT)

    private fun sideFieldValue(state: BattleStateView, side: BattleSide): Double =
        state.field.sideConditions.getValue(side).sumOf { effect ->
            val stacks = effect.stacks ?: 1
            when (canonicalId(effect.effectId)) {
                "stealthrock", "spikes", "toxicspikes", "stickyweb" -> -HAZARD_STACK_VALUE * stacks
                "reflect", "lightscreen", "auroraveil", "safeguard", "mist" -> BENEFICIAL_SIDE_EFFECT_VALUE
                else -> 0.0
            }
        }

    private fun canonicalId(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private const val MAX_STAGE_VALUE = 0.60
    private const val DEFAULT_STAGE_WEIGHT = 0.06
    private const val SPEED_CONTROL_VALUE = 0.15
    private const val HAZARD_STACK_VALUE = 0.10
    private const val BENEFICIAL_SIDE_EFFECT_VALUE = 0.15
    private val STAGE_WEIGHTS = mapOf(
        "attack" to 0.10,
        "atk" to 0.10,
        "specialattack" to 0.10,
        "spatk" to 0.10,
        "spa" to 0.10,
        "defense" to 0.08,
        "defence" to 0.08,
        "def" to 0.08,
        "specialdefense" to 0.08,
        "specialdefence" to 0.08,
        "spdef" to 0.08,
        "spd" to 0.08,
        "speed" to 0.08,
        "spe" to 0.08,
        "accuracy" to 0.06,
        "evasion" to 0.06,
    )
}
