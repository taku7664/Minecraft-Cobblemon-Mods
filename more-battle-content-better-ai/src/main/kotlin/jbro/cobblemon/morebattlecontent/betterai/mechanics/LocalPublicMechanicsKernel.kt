package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/**
 * Deterministic local projection of damage modifiers that are already public and unambiguous.
 *
 * This is deliberately separate from the shared standard-damage DTO: it does not rename the
 * base model into a final damage claim, and it never reads unrevealed opponent state.
 */
internal object LocalPublicMechanicsKernel {
    /** Small root-score risk for a legal but unrevealed immunity, without assuming usage rates. */
    fun hasUnconfirmedAbilityImmunity(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): Boolean {
        val details = candidate.moveDetails ?: return false
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || details.power <= 0.0) return false
        val target = LocalPublicMoveTargets.resolve(candidate, context, actingSide)
            .singleOrNull()?.takeIf { it.side != actingSide } ?: return false
        if (publicAbility(target, context) != null) return false
        val actor = context.state.pokemon.firstOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        if (LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, actor, target, context.state)) return false

        val abilityInferences = context.state.inferences.filter {
            it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_INFERENCE_CATEGORY
        }
        val ruledOut = abilityInferences.asSequence()
            .filter { it.confidence == BattleInferenceConfidence.RULED_OUT }
            .mapNotNull { it.candidateId?.let(::canonical) }
            .toSet()
        val inferred = abilityInferences.asSequence()
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .mapNotNull { it.candidateId?.let(::canonical) }
            .toSet()
        val legal = context.opponentTeamPreview?.pokemon.orEmpty().asSequence()
            .filter {
                canonical(it.speciesId) == canonical(target.speciesId) &&
                    it.formId?.let(::canonical) == target.formId?.let(::canonical)
            }
            .flatMap { it.buildCandidatePool?.abilities.orEmpty().asSequence() }
            .map { canonical(it.abilityId) }
            .toSet()
        val possible = (if (inferred.isNotEmpty()) inferred else legal) - ruledOut
        val moveType = canonical(details.typeId)
        val typeMultiplier = StandardTypeEffectiveness.multiplierForMove(
            candidate.moveId, details.typeId, target.knownTypeIds,
        )
        val hasBlockingCandidate = possible.any { ability ->
            LocalPublicAbilityState.isActive(context.state, target, ability) &&
                (ability in TYPE_IMMUNITY_ABILITIES[moveType].orEmpty() ||
                    ability == WONDER_GUARD && typeMultiplier < 2.0)
        }
        return hasBlockingCandidate && !projectMove(candidate, context, actingSide).publiclyNullified
    }

    fun publicDamageMultiplierAgainst(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
        target: BattlePokemonStateView,
    ): Double {
        val details = candidate.moveDetails ?: return 1.0
        val actor = context.state.pokemon.firstOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(
            candidate, actor, target, context.state,
        )
        if (specialTargetImmunity(
                candidate, context, actingSide, target,
                actor, LocalPublicAbilityState.effectiveKnownAbility(context.state, actor), ignoresAbility,
            )
        ) return 0.0
        val ignoresTypeImmunity = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY
        }
        val base = StandardTypeEffectiveness.multiplierAgainst(
            details.typeId,
            target.knownTypeIds,
            publicAbility(target, context),
            ignoreTypeImmunity = ignoresTypeImmunity,
            applyAbilities = !ignoresAbility,
            moveId = candidate.moveId,
        )
        return if (ignoresAbility) base else base * abilityDamageMultiplier(
            canonical(details.typeId), publicAbility(target, context),
        )
    }

    fun projectMove(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide = BattleSide.ALLY,
    ): LocalPublicMoveProjection {
        val details = candidate.moveDetails ?: return LocalPublicMoveProjection.neutral()
        val target = LocalPublicMoveTargets.resolve(candidate, context, actingSide).firstOrNull()
            ?: return LocalPublicMoveProjection.neutral()
        val actor = context.state.pokemon.firstOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        val actorAbility = LocalPublicAbilityState.effectiveKnownAbility(context.state, actor)
        val ignoresAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(
            candidate,
            actor,
            target,
            context.state,
        )
        if (specialTargetImmunity(candidate, context, actingSide, target, actor, actorAbility, ignoresAbility)) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }
        if (priorityMoveBlocked(candidate, context, actingSide, target, ignoresAbility)) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            return projectStatusMove(candidate, details, context, actingSide, ignoresAbility)
        }
        val moveType = canonical(details.typeId)
        val actorItem = LocalPublicItemState.activeItemId(context.state, actor)
        val targetAbility = publicAbility(target, context)
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
                targetAbility == WONDER_GUARD && publicTypeMultiplier?.let { it <= 1.0 } == true ||
                possibleAbilitiesAllBlock(target, context, moveType)
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
        val weatherMultiplier = weatherDamageMultiplier(
            moveId = canonicalOrNull(candidate.moveId),
            moveType = moveType,
            actorItem = actorItem,
            context = context,
        )
        if (weatherMultiplier == 0.0) {
            return LocalPublicMoveProjection(
                knownDamageMultiplier = 0.0,
                targetHpFraction = target.hpFraction,
                publiclyNullified = true,
            )
        }
        val terrainMultiplier = terrainDamageMultiplier(
            moveId = canonicalOrNull(candidate.moveId),
            moveType = moveType,
            actor = actor,
            target = target,
            context = context,
        )
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
            knownDamageMultiplier = abilityMultiplier * weatherMultiplier * terrainMultiplier * screenMultiplier,
            targetHpFraction = target.hpFraction,
            publiclyNullified = false,
        )
    }

    private fun specialTargetImmunity(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
        target: BattlePokemonStateView,
        actor: BattlePokemonStateView?,
        actorAbility: String?,
        ignoresAbility: Boolean,
    ): Boolean {
        val details = candidate.moveDetails ?: return false
        val types = target.knownTypeIds.mapTo(linkedSetOf(), ::canonical)
        val ability = publicAbility(target, context)
        val flags = details.effects?.mechanicFlags.orEmpty().mapTo(hashSetOf(), ::canonical)
        if (!ignoresAbility && when (ability) {
                WIND_RIDER -> WIND_FLAG in flags
                BULLETPROOF -> BULLET_FLAG in flags
                SOUNDPROOF -> SOUND_FLAG in flags &&
                    target.battlePokemonId != actor?.battlePokemonId
                TELEPATHY -> target.side == actingSide && details.damageCategory != BattleMoveDamageCategory.STATUS
                else -> false
            }
        ) return true
        if (!ignoresAbility && target.side != actingSide &&
            details.damageCategory == BattleMoveDamageCategory.STATUS
        ) {
            if (ability == GOOD_AS_GOLD) return true
            if (ability == MAGIC_BOUNCE && REFLECTABLE_FLAG in flags) return true
        }
        if (target.side != actingSide && targetsOpponent(candidate, actingSide) &&
            actorAbility == PRANKSTER && details.damageCategory == BattleMoveDamageCategory.STATUS && DARK in types
        ) {
            return true
        }
        if (POWDER_FLAG !in details.effects?.mechanicFlags.orEmpty()) return false
        val item = LocalPublicItemState.activeItemId(context.state, target)
        return GRASS in types || item == SAFETY_GOGGLES || !ignoresAbility && ability == OVERCOAT
    }

    private fun priorityMoveBlocked(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
        target: BattlePokemonStateView,
        ignoresAbility: Boolean,
    ): Boolean {
        if (target.side == actingSide) return false
        if (LocalPublicTurnOrder.effectivePriority(context.state, actingSide, candidate) <= 0) return false
        if (!targetsOpponent(candidate, actingSide)) return false
        val psychicTerrain = canonicalOrNull(context.state.field.terrain?.effectId) == PSYCHIC_TERRAIN
        if (psychicTerrain && LocalPublicTurnOrder.grounded(context.state, target)) return true
        if (ignoresAbility) return false
        return context.state.pokemon.any {
            it.side == target.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                publicAbility(it, context) in PRIORITY_BLOCKING_ABILITIES
        }
    }

    private fun targetsOpponent(candidate: BattleActionCandidate, actingSide: BattleSide): Boolean {
        val moveId = canonicalOrNull(candidate.moveId)
        val pattern = candidate.moveDetails?.targetPattern
        return candidate.targets.any { it.side != actingSide } || pattern in HOSTILE_TARGET_PATTERNS ||
            pattern == BattleMoveTargetPattern.ALL_ACTIVE && moveId in TARGET_ALL_PRIORITY_BLOCK_EXCEPTIONS
    }

    private fun projectStatusMove(
        candidate: BattleActionCandidate,
        details: jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView,
        context: BattleDecisionContext,
        actingSide: BattleSide,
        ignoresAbility: Boolean,
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
        val target = singleStatusTarget(candidate, context, actingSide)
            ?: return LocalPublicMoveProjection.neutral()
        val types = target.knownTypeIds.mapTo(linkedSetOf(), ::canonical)
        val ability = publicAbility(target, context)
        val moveId = canonicalOrNull(candidate.moveId)
        val nullified = targetStatuses.all { effect ->
            val status = canonical(requireNotNull(effect.valueId))
            target.statusId != null || when {
                status in POISON_STATUSES -> POISON in types || STEEL in types ||
                    !ignoresAbility && ability == "immunity"
                status in BURN_STATUSES -> FIRE in types ||
                    !ignoresAbility && (ability == "waterveil" || ability == "waterbubble")
                status in PARALYSIS_STATUSES -> ELECTRIC in types || !ignoresAbility && ability == "limber" ||
                    moveId == "thunderwave" && GROUND in types
                status in SLEEP_STATUSES -> !ignoresAbility && ability in SLEEP_IMMUNITY_ABILITIES
                status in FREEZE_STATUSES -> ICE in types || !ignoresAbility && ability == "magmaarmor"
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
        moveId: String?,
        moveType: String,
        actorItem: String?,
        context: BattleDecisionContext,
    ): Double {
        if (actorItem == UTILITY_UMBRELLA) return 1.0
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(context.state)
        if (moveId == HYDRO_STEAM && weather in SUN_WEATHER) return 1.5
        return when (weather) {
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

    private fun terrainDamageMultiplier(
        moveId: String?,
        moveType: String,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView,
        context: BattleDecisionContext,
    ): Double {
        val actorGrounded = actor?.let { LocalPublicTurnOrder.grounded(context.state, it) } == true
        val targetGrounded = LocalPublicTurnOrder.grounded(context.state, target)
        val actorSemiInvulnerable = actor?.isSemiInvulnerable() == true
        val targetSemiInvulnerable = target.isSemiInvulnerable()
        return when (LocalPublicFieldMechanics.terrainId(context.state)) {
            ELECTRIC_TERRAIN -> if (
                moveType == ELECTRIC && actorGrounded && !actorSemiInvulnerable
            ) TERRAIN_TYPE_BOOST else 1.0
            GRASSY_TERRAIN -> when {
                moveId in GRASSY_TERRAIN_WEAKENED_MOVES && targetGrounded && !targetSemiInvulnerable -> 0.5
                moveType == GRASS && actorGrounded -> TERRAIN_TYPE_BOOST
                else -> 1.0
            }
            PSYCHIC_TERRAIN -> if (
                moveType == PSYCHIC && actorGrounded && !actorSemiInvulnerable
            ) TERRAIN_TYPE_BOOST else 1.0
            MISTY_TERRAIN -> if (
                moveType == DRAGON && targetGrounded && !targetSemiInvulnerable
            ) 0.5 else 1.0
            else -> 1.0
        }
    }

    private fun BattlePokemonStateView.isSemiInvulnerable(): Boolean = knownVolatileEffectIds.any {
        canonical(it) in SEMI_INVULNERABLE_VOLATILES
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

    // Status effects retain their single-target contract; one immune spread target is not all targets.
    private fun singleStatusTarget(
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
    ): String? = LocalPublicAbilityState.effectiveKnownAbility(context.state, target) ?: context.state.inferences.asSequence()
        .filter { it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_INFERENCE_CATEGORY }
        .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
        .mapNotNull { it.candidateId?.let(::canonical) }
        .distinct()
        .singleOrNull()
        ?.takeIf { LocalPublicAbilityState.isActive(context.state, target, it) }

    /** All remaining public candidates must block; hidden classification is not exclusion evidence. */
    private fun possibleAbilitiesAllBlock(
        target: BattlePokemonStateView,
        context: BattleDecisionContext,
        moveType: String,
    ): Boolean {
        if (target.knownAbilityId != null) return false
        if (!LocalPublicAbilityState.isActive(context.state, target, "levitate")) return false
        val blocking = TYPE_IMMUNITY_ABILITIES[moveType].orEmpty()
        if (blocking.isEmpty()) return false
        val possible = context.state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && it.categoryId == ABILITY_INFERENCE_CATEGORY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .mapNotNull { it.candidateId?.let(::canonical) }
            .distinct()
            .toList()
        return possible.isNotEmpty() && possible.all { it in blocking }
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
    private const val PRANKSTER = "prankster"
    private const val POWDER_FLAG = "powder"
    private const val WIND_FLAG = "wind"
    private const val BULLET_FLAG = "bullet"
    private const val SOUND_FLAG = "sound"
    private const val WIND_RIDER = "windrider"
    private const val BULLETPROOF = "bulletproof"
    private const val SOUNDPROOF = "soundproof"
    private const val TELEPATHY = "telepathy"
    private const val GOOD_AS_GOLD = "goodasgold"
    private const val MAGIC_BOUNCE = "magicbounce"
    private const val REFLECTABLE_FLAG = "reflectable"
    private const val SAFETY_GOGGLES = "safetygoggles"
    private const val OVERCOAT = "overcoat"
    private val PRIORITY_BLOCKING_ABILITIES = setOf("armortail", "queenlymajesty", "dazzling")
    private const val PSYCHIC_TERRAIN = "psychicterrain"
    private const val ELECTRIC_TERRAIN = "electricterrain"
    private const val GRASSY_TERRAIN = "grassyterrain"
    private const val MISTY_TERRAIN = "mistyterrain"
    private const val TERRAIN_TYPE_BOOST = 5325.0 / 4096.0
    private val GRASSY_TERRAIN_WEAKENED_MOVES = setOf("earthquake", "bulldoze", "magnitude")
    private val SEMI_INVULNERABLE_VOLATILES = setOf(
        "bounce", "dig", "dive", "fly", "phantomforce", "shadowforce", "skydrop",
    )
    private val HOSTILE_TARGET_PATTERNS = setOf(
        BattleMoveTargetPattern.SELECTED,
        BattleMoveTargetPattern.SELECTED_OPPONENT,
        BattleMoveTargetPattern.RANDOM_OPPONENT,
        BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT,
    )
    private val TARGET_ALL_PRIORITY_BLOCK_EXCEPTIONS = setOf("perishsong", "flowershield", "rototiller")
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
    private const val HYDRO_STEAM = "hydrosteam"
    private const val ELECTRIC = "electric"
    private const val GRASS = "grass"
    private const val DARK = "dark"
    private const val ICE = "ice"
    private const val GHOST = "ghost"
    private const val DRAGON = "dragon"
    private const val PSYCHIC = "psychic"
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
