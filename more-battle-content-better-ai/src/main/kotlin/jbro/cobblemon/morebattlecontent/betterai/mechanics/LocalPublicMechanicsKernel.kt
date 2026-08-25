package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
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
    fun projectMove(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): LocalPublicMoveProjection {
        val details = candidate.moveDetails ?: return LocalPublicMoveProjection.neutral()
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            return projectStatusMove(candidate, details, context, actingSide)
        }
        val target = singleOpponentTarget(candidate, context, actingSide) ?: return LocalPublicMoveProjection.neutral()
        val actor = context.state.pokemon.firstOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        val moveType = canonical(details.typeId)
        val actorAbility = canonicalOrNull(actor?.knownAbilityId)
        val actorItem = canonicalOrNull(actor?.knownHeldItemId)
        val targetAbility = publicAbility(target, context)
        val ignoresAbility = actorAbility in ABILITY_IGNORING_ABILITIES ||
            details.effects?.effects?.any { it.kind == BattleMoveEffectKind.IGNORE_ABILITY } == true
        val ignoresTypeImmunity = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY
        }
        val publicTypeMultiplier = candidate.facts?.typeChartMultiplier ?: target.knownTypeIds
            .takeIf { it.isNotEmpty() }
            ?.let { StandardTypeEffectiveness.multiplier(details.typeId, it, ignoresTypeImmunity) }

        if (publicTypeMultiplier == 0.0) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }

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
        val screenMultiplier = if (details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.ALWAYS_CRITICAL
        }) 1.0 else screenDamageMultiplier(
            category = details.damageCategory,
            actorAbility = actorAbility,
            moveId = canonicalOrNull(candidate.moveId),
            context = context,
            targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY,
        )
        return LocalPublicMoveProjection(
            knownDamageMultiplier = abilityMultiplier * weatherMultiplier * screenMultiplier,
            targetHpFraction = target.hpFraction,
            publiclyNullified = false,
        )
    }

    private fun projectStatusMove(
        candidate: BattleActionCandidate,
        details: jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): LocalPublicMoveProjection {
        val declared = details.effects?.effects.orEmpty().filter { (it.probability ?: 1.0) > 0.0 }
        val targetStatuses = declared.filter {
            it.kind == BattleMoveEffectKind.STATUS &&
                it.target == jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget.SELECTED_TARGET &&
                it.valueId != null
        }
        if (targetStatuses.isEmpty() || targetStatuses.size != declared.size) {
            return LocalPublicMoveProjection.neutral()
        }
        val target = singleOpponentTarget(candidate, context, actingSide)
            ?: return LocalPublicMoveProjection.neutral()
        val types = target.knownTypeIds.mapTo(linkedSetOf(), ::canonical)
        val ability = publicAbility(target, context)
        val moveId = canonicalOrNull(candidate.moveId)
        val nullified = targetStatuses.all { effect ->
            val status = canonical(requireNotNull(effect.valueId))
            target.statusId != null || when {
                status in POISON_STATUSES -> POISON in types || STEEL in types || ability == "immunity"
                status in BURN_STATUSES -> FIRE in types || ability == "waterveil" || ability == "waterbubble"
                status in PARALYSIS_STATUSES -> ELECTRIC in types || ability == "limber" ||
                    moveId == "thunderwave" && GROUND in types
                status in SLEEP_STATUSES -> ability in SLEEP_IMMUNITY_ABILITIES
                status in FREEZE_STATUSES -> ICE in types || ability == "magmaarmor"
                else -> false
            }
        }
        return LocalPublicMoveProjection(
            knownDamageMultiplier = 1.0,
            targetHpFraction = target.hpFraction,
            publiclyNullified = nullified,
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
        targetSide: BattleSide,
    ): Double {
        if (actorAbility == INFILTRATOR || moveId in SCREEN_BREAKING_MOVES) return 1.0
        val conditions = context.state.field.sideConditions.getValue(targetSide)
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
        actingSide: BattleSide,
    ): BattlePokemonStateView? {
        val explicitTarget = candidate.targets.singleOrNull()
        if (explicitTarget != null) {
            return context.state.pokemon.firstOrNull {
                it.side == explicitTarget.side && it.activeSlot == explicitTarget.slot && !it.fainted
            }
        }
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        return context.state.pokemon.filter {
            it.side == targetSide && it.activeSlot != null && !it.fainted
        }.singleOrNull()
    }

    private fun publicAbility(
        target: BattlePokemonStateView,
        context: BattleDecisionContext,
    ): String? = canonicalOrNull(target.knownAbilityId) ?: context.state.inferences.asSequence()
        .filter { it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_INFERENCE_CATEGORY }
        .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
        .mapNotNull { it.candidateId?.let(::canonical) }
        .distinct()
        .singleOrNull()

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
    private val POISON_STATUSES = setOf("psn", "poison", "poisoned", "tox", "toxic", "badlypoisoned")
    private val BURN_STATUSES = setOf("brn", "burn", "burned", "burnt")
    private val PARALYSIS_STATUSES = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val SLEEP_STATUSES = setOf("slp", "sleep", "asleep")
    private val FREEZE_STATUSES = setOf("frz", "freeze", "frozen")
    private val SLEEP_IMMUNITY_ABILITIES = setOf("insomnia", "vitalspirit", "sweetveil")

    private const val GROUND = "ground"
    private const val FIRE = "fire"
    private const val WATER = "water"
    private const val ELECTRIC = "electric"
    private const val GRASS = "grass"
    private const val ICE = "ice"
    private const val GHOST = "ghost"
    private const val POISON = "poison"
    private const val STEEL = "steel"
    private const val WONDER_GUARD = "wonderguard"
    private const val UTILITY_UMBRELLA = "utilityumbrella"
    private const val INFILTRATOR = "infiltrator"
    private const val ABILITY_INFERENCE_CATEGORY = "ability"
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
