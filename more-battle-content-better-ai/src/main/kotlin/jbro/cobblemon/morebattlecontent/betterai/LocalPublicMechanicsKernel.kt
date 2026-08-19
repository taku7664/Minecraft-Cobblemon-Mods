package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/**
 * Deterministic local projection of damage modifiers that are already public and unambiguous.
 *
 * This is deliberately separate from the shared standard-damage DTO: it does not rename the
 * base model into a final damage claim, and it never reads unrevealed opponent state.
 */
internal object LocalPublicMechanicsKernel {
    fun projectMove(candidate: BattleActionCandidate, context: BattleDecisionContext): LocalPublicMoveProjection {
        val details = candidate.moveDetails ?: return LocalPublicMoveProjection.neutral()
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return LocalPublicMoveProjection.neutral()
        val target = singleOpponentTarget(candidate, context) ?: return LocalPublicMoveProjection.neutral()
        val actor = LocalPublicPositionFacts.activeAlly(candidate, context)
        val moveType = canonical(details.typeId)
        val actorAbility = canonicalOrNull(actor?.knownAbilityId)
        val actorItem = canonicalOrNull(actor?.knownHeldItemId)
        val targetAbility = canonicalOrNull(target.knownAbilityId)
        val ignoresAbility = actorAbility in ABILITY_IGNORING_ABILITIES ||
            details.effects?.effects?.any { it.kind == BattleMoveEffectKind.IGNORE_ABILITY } == true
        val publicTypeMultiplier = candidate.facts?.typeChartMultiplier ?: target.knownTypeIds
            .takeIf { it.isNotEmpty() }
            ?.let { StandardTypeEffectiveness.multiplier(details.typeId, it) }

        val abilityImmune = !ignoresAbility && (
            targetAbility in TYPE_IMMUNITY_ABILITIES[moveType].orEmpty() ||
                targetAbility == WONDER_GUARD && publicTypeMultiplier?.let { it <= 1.0 } == true
            )
        if (abilityImmune) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }

        val abilityMultiplier = if (ignoresAbility) {
            1.0
        } else {
            abilityDamageMultiplier(moveType, targetAbility)
        }
        val weatherMultiplier = weatherDamageMultiplier(moveType, actorItem, context)
        if (weatherMultiplier == 0.0) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }
        val screenMultiplier = screenDamageMultiplier(
            category = details.damageCategory,
            actorAbility = actorAbility,
            moveId = canonicalOrNull(candidate.moveId),
            context = context,
        )
        return LocalPublicMoveProjection(
            knownDamageMultiplier = abilityMultiplier * weatherMultiplier * screenMultiplier,
            targetHpFraction = target.hpFraction,
            publiclyNullified = false,
        )
    }

    private fun abilityDamageMultiplier(moveType: String, ability: String?): Double = when (ability) {
        "thickfat" -> if (moveType == FIRE || moveType == ICE) 0.5 else 1.0
        "heatproof" -> if (moveType == FIRE) 0.5 else 1.0
        "waterbubble" -> if (moveType == FIRE) 0.5 else 1.0
        "purifyingsalt" -> if (moveType == GHOST) 0.5 else 1.0
        "fluffy" -> if (moveType == FIRE) 2.0 else 1.0
        "dryskin" -> if (moveType == FIRE) 1.25 else 1.0
        else -> 1.0
    }

    private fun weatherDamageMultiplier(
        moveType: String,
        actorItem: String?,
        context: BattleDecisionContext,
    ): Double {
        if (actorItem == UTILITY_UMBRELLA || weatherSuppressed(context)) return 1.0
        return when (canonicalOrNull(context.state.field.weather?.effectId)) {
            in HEAVY_RAIN_WEATHER -> when (moveType) {
                WATER -> 1.5
                FIRE -> 0.0
                else -> 1.0
            }
            in HARSH_SUN_WEATHER -> when (moveType) {
                FIRE -> 1.5
                WATER -> 0.0
                else -> 1.0
            }
            in RAIN_WEATHER -> when (moveType) {
                WATER -> 1.5
                FIRE -> 0.5
                else -> 1.0
            }
            in SUN_WEATHER -> when (moveType) {
                FIRE -> 1.5
                WATER -> 0.5
                else -> 1.0
            }
            else -> 1.0
        }
    }

    private fun weatherSuppressed(context: BattleDecisionContext): Boolean = context.state.pokemon.any {
        it.activeSlot != null && !it.fainted && canonicalOrNull(it.knownAbilityId) in WEATHER_SUPPRESSION_ABILITIES
    }

    private fun screenDamageMultiplier(
        category: BattleMoveDamageCategory,
        actorAbility: String?,
        moveId: String?,
        context: BattleDecisionContext,
    ): Double {
        if (actorAbility == INFILTRATOR || moveId in SCREEN_BREAKING_MOVES) return 1.0
        val conditions = context.state.field.sideConditions.getValue(BattleSide.OPPONENT)
            .mapTo(linkedSetOf()) { canonical(it.effectId) }
        val reduced = AURORA_VEIL in conditions || when (category) {
            BattleMoveDamageCategory.PHYSICAL -> REFLECT in conditions
            BattleMoveDamageCategory.SPECIAL -> LIGHT_SCREEN in conditions
            BattleMoveDamageCategory.STATUS -> false
        }
        if (!reduced) return 1.0
        return if (context.state.format == BattleFormat.DOUBLE) DOUBLE_SCREEN_MULTIPLIER else SINGLE_SCREEN_MULTIPLIER
    }

    private fun singleOpponentTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? {
        val slots = candidate.targets.filter { it.side == BattleSide.OPPONENT }.map { it.slot }
        if (slots.size == 1) {
            return context.state.pokemon.firstOrNull {
                it.side == BattleSide.OPPONENT && it.activeSlot == slots.single() && !it.fainted
            }
        }
        return context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
        }.singleOrNull()
    }

    private fun canonicalOrNull(value: String?): String? = value?.let(::canonical)

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private val TYPE_IMMUNITY_ABILITIES = mapOf(
        GROUND to setOf("levitate", "eartheater"),
        FIRE to setOf("flashfire", "wellbakedbody"),
        WATER to setOf("waterabsorb", "dryskin", "stormdrain"),
        ELECTRIC to setOf("voltabsorb", "lightningrod", "motordrive"),
        GRASS to setOf("sapsipper"),
    )
    private val ABILITY_IGNORING_ABILITIES = setOf("moldbreaker", "teravolt", "turboblaze")
    private val WEATHER_SUPPRESSION_ABILITIES = setOf("airlock", "cloudnine")
    private val RAIN_WEATHER = setOf("rain", "raindance")
    private val SUN_WEATHER = setOf("sun", "sunnyday")
    private val HEAVY_RAIN_WEATHER = setOf("heavyrain", "primordialsea")
    private val HARSH_SUN_WEATHER = setOf("harshsunlight", "desolateland")
    private val SCREEN_BREAKING_MOVES = setOf("brickbreak", "psychicfangs", "ragingbull")

    private const val GROUND = "ground"
    private const val FIRE = "fire"
    private const val WATER = "water"
    private const val ELECTRIC = "electric"
    private const val GRASS = "grass"
    private const val ICE = "ice"
    private const val GHOST = "ghost"
    private const val WONDER_GUARD = "wonderguard"
    private const val UTILITY_UMBRELLA = "utilityumbrella"
    private const val INFILTRATOR = "infiltrator"
    private const val REFLECT = "reflect"
    private const val LIGHT_SCREEN = "lightscreen"
    private const val AURORA_VEIL = "auroraveil"
    private const val SINGLE_SCREEN_MULTIPLIER = 0.5
    private const val DOUBLE_SCREEN_MULTIPLIER = 2.0 / 3.0
}

internal data class LocalPublicMoveProjection(
    val knownDamageMultiplier: Double,
    val targetHpFraction: Double?,
    val publiclyNullified: Boolean,
) {
    companion object {
        fun neutral() = LocalPublicMoveProjection(1.0, null, false)
    }
}
