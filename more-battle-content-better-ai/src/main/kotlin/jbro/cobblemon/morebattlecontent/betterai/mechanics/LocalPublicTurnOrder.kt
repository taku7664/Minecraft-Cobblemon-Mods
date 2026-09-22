package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * How likely one side is to act before the other, from public information alone.
 *
 * The turn projector has always worked this out for itself. The facts the root ranking is built from
 * carry a field for it - `actsFirstProbability` - that nothing ever filled, so the ranking had no
 * notion of turn order at all and the whole subject lived inside the search. At the lowest difficulty
 * the search is one ply and its voice is scaled down twice over, which means the trainers a player
 * meets first are the ones least able to reason about who moves when.
 *
 * Both Speeds are treated as uniform over their public range, which is the honest reading of a range
 * that refuses IVs, EVs and nature: no value inside it is claimed to be likelier than another. Ties
 * split evenly, matching the coin flip the engine performs.
 */
internal object LocalPublicTurnOrder {
    /** Priority after deterministic, publicly known move and ability modifiers. */
    fun effectivePriority(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): Int {
        val details = action.moveDetails ?: return 0
        val actor = active(state, side, action.actorSlot)
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, actor).orEmpty()
        val moveId = canonical(action.moveId.orEmpty())
        val healingMove = details.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION || it.kind == BattleMoveEffectKind.DRAIN_FRACTION
        } || "heal" in details.effects?.mechanicFlags.orEmpty()
        val modifier = when {
            ability == PRANKSTER && details.damageCategory == BattleMoveDamageCategory.STATUS -> 1
            ability == GALE_WINGS && actor?.hpFraction == 1.0 && canonical(details.typeId) == FLYING -> 1
            ability == TRIAGE && healingMove -> 3
            moveId == GRASSY_GLIDE && grassyTerrainActive(state) && actor?.let { grounded(state, it) } == true -> 1
            else -> 0
        }
        return details.priority + modifier
    }

    /** Public chance to jump to the front of the current priority bracket. */
    fun fractionalPriorityChance(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): Double {
        if (alwaysLastWithinPriority(state, side, action)) return 0.0
        val actor = active(state, side, action.actorSlot) ?: return 0.0
        val chances = buildList {
            if (LocalPublicAbilityState.effectiveKnownAbility(state, actor) == QUICK_DRAW &&
                action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
            ) add(QUICK_DRAW_CHANCE)
            if (!LocalPublicFieldMechanics.magicRoomActive(state) &&
                canonical(actor.knownHeldItemId.orEmpty()) == QUICK_CLAW
            ) add(QUICK_CLAW_CHANCE)
        }
        return 1.0 - chances.fold(1.0) { none, chance -> none * (1.0 - chance) }
    }

    fun alwaysLastWithinPriority(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): Boolean {
        val actor = active(state, side, action.actorSlot) ?: return false
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, actor).orEmpty()
        val item = canonical(actor.knownHeldItemId.orEmpty())
        return ability == STALL ||
            ability == MYCELIUM_MIGHT && action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS ||
            !LocalPublicFieldMechanics.magicRoomActive(state) && item in ALWAYS_LAST_ITEMS
    }

    /** Action-aware ordering used by recursive turn projection. */
    fun actsFirstProbability(
        state: BattleStateView,
        firstSide: BattleSide,
        firstAction: BattleActionCandidate,
        secondSide: BattleSide,
        secondAction: BattleActionCandidate,
    ): Double? {
        val firstPriority = effectivePriority(state, firstSide, firstAction)
        val secondPriority = effectivePriority(state, secondSide, secondAction)
        if (firstPriority != secondPriority) return if (firstPriority > secondPriority) 1.0 else 0.0
        val first = active(state, firstSide, firstAction.actorSlot) ?: return null
        val second = active(state, secondSide, secondAction.actorSlot) ?: return null
        val speedProbability = speedOrderProbability(state, first, second) ?: return null
        val firstDistribution = fractionalPriorityDistribution(state, firstSide, firstAction)
        val secondDistribution = fractionalPriorityDistribution(state, secondSide, secondAction)
        return firstDistribution.sumOf { (firstFraction, firstChance) ->
            secondDistribution.sumOf { (secondFraction, secondChance) ->
                val orderChance = when {
                    firstFraction > secondFraction -> 1.0
                    firstFraction < secondFraction -> 0.0
                    else -> speedProbability
                }
                firstChance * secondChance * orderChance
            }
        }
    }

    /** Action-aware comparison against an otherwise ordinary priority-zero reply. */
    fun actsFirstProbability(
        state: BattleStateView,
        actorSide: BattleSide,
        actorSlot: Int?,
        actorAction: BattleActionCandidate,
        opponentPriority: Int,
    ): Double? {
        val priority = effectivePriority(state, actorSide, actorAction)
        if (priority != opponentPriority) return if (priority > opponentPriority) 1.0 else 0.0
        val actor = active(state, actorSide, actorSlot) ?: return null
        val opponent = state.pokemon.firstOrNull {
            it.side != actorSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val speedProbability = speedOrderProbability(state, actor, opponent) ?: return null
        if (alwaysLastWithinPriority(state, actorSide, actorAction)) return 0.0
        val quickChance = fractionalPriorityChance(state, actorSide, actorAction)
        return quickChance + (1.0 - quickChance) * speedProbability
    }

    fun actsFirstProbability(
        state: BattleStateView,
        actorSide: BattleSide,
        actorSlot: Int?,
        actorPriority: Int,
        opponentPriority: Int,
    ): Double? {
        if (actorPriority != opponentPriority) return if (actorPriority > opponentPriority) 1.0 else 0.0
        val actor = active(state, actorSide, actorSlot) ?: return null
        val opponent = state.pokemon.firstOrNull {
            it.side != actorSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        return speedOrderProbability(state, actor, opponent)
    }

    /** P(a > b) plus half of P(a == b), for two independent uniform integer ranges. */
    fun uniformGreaterProbability(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val aValues = (a.second - a.first + 1).toLong()
        val bValues = (b.second - b.first + 1).toLong()
        if (aValues <= 0L || bValues <= 0L) return 0.5
        var greater = 0L
        var equal = 0L
        // Counted over b, so the cost is the width of one range rather than the product of both.
        for (bValue in b.first..b.second) {
            greater += (a.second.toLong() - maxOf(a.first, bValue + 1) + 1).coerceAtLeast(0L)
            if (bValue in a.first..a.second) equal++
        }
        return (greater + equal / 2.0) / (aValues * bValues).toDouble()
    }

    fun effectiveSpeed(state: BattleStateView, pokemon: BattlePokemonStateView): Pair<Int, Int>? {
        val speed = pokemon.combatStats?.speed?.let { LocalKnownStatMechanics.speed(it, pokemon, state) } ?: return null
        val stage = pokemon.statStages.entries
            .firstOrNull { canonical(it.key) in SPEED_ALIASES }?.value?.coerceIn(-6, 6) ?: 0
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon).orEmpty()
        val statused = pokemon.statusId != null
        val paralysis = if (canonical(pokemon.statusId.orEmpty()) in PARALYSIS_IDS && ability != QUICK_FEET) 0.5 else 1.0
        val weather = LocalPublicFieldMechanics.effectiveWeatherId(state)
        val abilityMultiplier = when {
            ability == QUICK_FEET && statused -> 1.5
            ability == SWIFT_SWIM && weather in RAIN_WEATHERS -> 2.0
            ability == CHLOROPHYLL && weather in SUN_WEATHERS -> 2.0
            ability == SAND_RUSH && weather == SANDSTORM -> 2.0
            ability == SLUSH_RUSH && weather in SNOW_WEATHERS -> 2.0
            ability == SURGE_SURFER && LocalPublicFieldMechanics.terrainId(state) == ELECTRIC_TERRAIN -> 2.0
            else -> 1.0
        }
        val tailwind = if (
            state.field.sideConditions.getValue(pokemon.side).any {
                val remaining = it.remainingTurns
                canonical(it.effectId) == TAILWIND && (remaining == null || remaining > 0)
            }
        ) {
            2.0
        } else {
            1.0
        }
        val multiplier = paralysis * abilityMultiplier * tailwind
        return (applyStage(speed.minimum, stage) * multiplier).toInt().coerceAtLeast(1) to
            (applyStage(speed.maximum, stage) * multiplier).toInt().coerceAtLeast(1)
    }

    fun grounded(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean {
        val volatiles = pokemon.knownVolatileEffectIds.mapTo(hashSetOf(), ::canonical)
        if (gravityActive(state) || volatiles.any { it in FORCED_GROUNDED_VOLATILES }) return true
        val itemsActive = !LocalPublicFieldMechanics.magicRoomActive(state)
        if (itemsActive && canonical(pokemon.knownHeldItemId.orEmpty()) == IRON_BALL) return true
        if (pokemon.knownTypeIds.any { canonical(it) == FLYING }) return false
        if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == LEVITATE) return false
        if (itemsActive && canonical(pokemon.knownHeldItemId.orEmpty()) == AIR_BALLOON) return false
        return true
    }

    private fun fractionalPriorityDistribution(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): List<Pair<Int, Double>> {
        if (alwaysLastWithinPriority(state, side, action)) return listOf(-1 to 1.0)
        val chance = fractionalPriorityChance(state, side, action)
        return if (chance > 0.0) listOf(1 to chance, 0 to 1.0 - chance) else listOf(0 to 1.0)
    }

    private fun speedOrderProbability(
        state: BattleStateView,
        first: BattlePokemonStateView,
        second: BattlePokemonStateView,
    ): Double? {
        val firstSpeed = effectiveSpeed(state, first) ?: return null
        val secondSpeed = effectiveSpeed(state, second) ?: return null
        val faster = uniformGreaterProbability(firstSpeed, secondSpeed)
        return if (LocalPublicFieldMechanics.trickRoomActive(state)) 1.0 - faster else faster
    }

    private fun grassyTerrainActive(state: BattleStateView): Boolean =
        canonical(state.field.terrain?.effectId.orEmpty()) == GRASSY_TERRAIN

    private fun gravityActive(state: BattleStateView): Boolean = state.field.globalEffects.any {
        val remaining = it.remainingTurns
        canonical(it.effectId) == GRAVITY && (remaining == null || remaining > 0)
    }

    private fun active(state: BattleStateView, side: BattleSide, slot: Int?): BattlePokemonStateView? =
        state.pokemon.firstOrNull {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                (slot == null || it.activeSlot == slot)
        }

    private fun applyStage(value: Int, stage: Int): Int = (
        if (stage >= 0) value * (2 + stage) / 2 else value * 2 / (2 - stage)
        ).coerceAtLeast(1)

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val TAILWIND = "tailwind"
    private const val PRANKSTER = "prankster"
    private const val GALE_WINGS = "galewings"
    private const val TRIAGE = "triage"
    private const val QUICK_DRAW = "quickdraw"
    private const val STALL = "stall"
    private const val MYCELIUM_MIGHT = "myceliummight"
    private const val QUICK_CLAW = "quickclaw"
    private const val GRASSY_GLIDE = "grassyglide"
    private const val GRASSY_TERRAIN = "grassyterrain"
    private const val FLYING = "flying"
    private const val GRAVITY = "gravity"
    private const val IRON_BALL = "ironball"
    private const val AIR_BALLOON = "airballoon"
    private const val LEVITATE = "levitate"
    private const val SWIFT_SWIM = "swiftswim"
    private const val CHLOROPHYLL = "chlorophyll"
    private const val SAND_RUSH = "sandrush"
    private const val SLUSH_RUSH = "slushrush"
    private const val SURGE_SURFER = "surgesurfer"
    private const val QUICK_FEET = "quickfeet"
    private const val SANDSTORM = "sandstorm"
    private const val ELECTRIC_TERRAIN = "electricterrain"
    private const val QUICK_DRAW_CHANCE = 0.3
    private const val QUICK_CLAW_CHANCE = 0.2
    private val ALWAYS_LAST_ITEMS = setOf("laggingtail", "fullincense")
    private val FORCED_GROUNDED_VOLATILES = setOf("ingrain", "smackdown", "thousandarrows")
    private val SPEED_ALIASES = setOf("speed", "spe")
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val RAIN_WEATHERS = setOf("raindance", "rain", "primordialsea", "heavyrain")
    private val SUN_WEATHERS = setOf("sunnyday", "sun", "desolateland", "harshsunshine")
    private val SNOW_WEATHERS = setOf("hail", "snow", "snowscape")
}
