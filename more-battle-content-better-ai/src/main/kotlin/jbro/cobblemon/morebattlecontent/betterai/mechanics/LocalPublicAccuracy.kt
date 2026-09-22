package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Resolves every deterministic accuracy modifier exposed by the public battle state. */
internal object LocalPublicAccuracy {
    fun probability(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): Double {
        return probability(candidate, context.state, actingSide)
    }

    fun probability(
        candidate: BattleActionCandidate,
        state: BattleStateView,
        actingSide: BattleSide,
    ): Double {
        var base = candidate.facts?.baseAccuracyProbability
            ?: candidate.moveDetails?.accuracy?.div(100.0)
            ?: 0.0
        val actor = state.pokemon.singleOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return base.coerceIn(0.0, 1.0)
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val explicit = candidate.targets.singleOrNull()
        val target = if (explicit != null) {
            state.pokemon.singleOrNull {
                it.side == explicit.side && it.activeSlot == explicit.slot && !it.fainted && it.hpFraction > 0.0
            }
        } else {
            state.pokemon.singleOrNull {
                it.side == targetSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
        }
        val actorAbility = canonical(actor.knownAbilityId)
        val targetAbility = canonical(target?.knownAbilityId)
        if (actorAbility == NO_GUARD || targetAbility == NO_GUARD) return 1.0

        val moveId = canonical(candidate.moveId)
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(state)
        val targetIgnoresWeather = canonical(target?.knownHeldItemId) == UTILITY_UMBRELLA
        when {
            moveId == BLIZZARD && weather in SNOW_WEATHER -> return 1.0
            moveId in RAIN_ACCURATE_MOVES && !targetIgnoresWeather && weather in RAIN_WEATHER -> return 1.0
            moveId in RAIN_ACCURATE_MOVES && !targetIgnoresWeather && weather in SUN_WEATHER -> base = 0.5
        }

        if (actorAbility == COMPOUND_EYES) base *= COMPOUND_EYES_MODIFIER
        if (
            actorAbility == HUSTLE &&
            candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.PHYSICAL
        ) base *= HUSTLE_ACCURACY_MODIFIER

        val accuracyStage = actor.stage("accuracy", "acc")
        val ignoresEvasion = candidate.moveDetails?.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_EVASION_STAGES
        }
        val evasionStage = if (ignoresEvasion) 0 else target?.stage("evasion", "eva") ?: 0
        val combined = (accuracyStage - evasionStage).coerceIn(-6, 6)
        val multiplier = if (combined >= 0) (3.0 + combined) / 3.0 else 3.0 / (3.0 - combined)
        return (base * multiplier).coerceIn(0.0, 1.0)
    }

    private fun BattlePokemonStateView.stage(vararg aliases: String): Int = statStages.entries.firstOrNull {
        canonical(it.key) in aliases
    }?.value?.coerceIn(-6, 6) ?: 0

    private fun canonical(value: String?): String = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()

    private const val NO_GUARD = "noguard"
    private const val COMPOUND_EYES = "compoundeyes"
    private const val HUSTLE = "hustle"
    private const val UTILITY_UMBRELLA = "utilityumbrella"
    private const val BLIZZARD = "blizzard"
    private val RAIN_ACCURATE_MOVES = setOf("thunder", "hurricane")
    private val RAIN_WEATHER = setOf("rain", "raindance", "primordialsea", "heavyrain")
    private val SUN_WEATHER = setOf("sun", "sunnyday", "desolateland", "harshsunlight")
    private val SNOW_WEATHER = setOf("snow", "snowscape", "hail")
    private const val COMPOUND_EYES_MODIFIER = 5325.0 / 4096.0
    private const val HUSTLE_ACCURACY_MODIFIER = 3277.0 / 4096.0
}
