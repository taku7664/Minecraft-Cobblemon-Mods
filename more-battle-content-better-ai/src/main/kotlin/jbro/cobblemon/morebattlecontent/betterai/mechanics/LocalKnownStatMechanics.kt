package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Applies deterministic power and stat modifiers whose item or ability is public. */
internal object LocalKnownStatMechanics {
    fun effectivePower(
        basePowers: Set<Int>,
        actor: BattlePokemonStateView,
        state: BattleStateView,
        action: BattleActionCandidate,
    ): BattleIntegerRange {
        require(basePowers.isNotEmpty())
        val doubled = (
            ROUND_POWER_DOUBLED_TAG in action.tags ||
            ACTS_BEFORE_TARGET_POWER_DOUBLED_TAG in action.tags ||
            TARGET_ALREADY_ACTED_POWER_DOUBLED_TAG in action.tags ||
            DAMAGED_BY_TARGET_POWER_DOUBLED_TAG in action.tags ||
            DAMAGED_TARGET_POWER_DOUBLED_TAG in action.tags
        )
        val doublingMultiplier = if (doubled) 2 else 1
        val technician = LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "technician"
        val turnMultiplier = turnPowerMultiplier(action)
        fun resolve(value: Int): Int {
            val resolvedBasePower = value * doublingMultiplier
            val technicianMultiplier = if (technician && resolvedBasePower <= 60) 1.5 else 1.0
            return (resolvedBasePower * technicianMultiplier * turnMultiplier).toInt().coerceAtLeast(1)
        }
        val candidates = basePowers.map(::resolve)
        return BattleIntegerRange(candidates.min(), candidates.max())
    }

    fun turnPowerMultiplier(action: BattleActionCandidate): Double = action.tags
        .firstOrNull { it.startsWith(TURN_POWER_MULTIPLIER_TAG_PREFIX) }
        ?.substringAfter(TURN_POWER_MULTIPLIER_TAG_PREFIX)
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 1.0 }
        ?: 1.0

    const val TURN_POWER_MULTIPLIER_TAG_PREFIX = "better_ai:turn_power_multiplier="
    const val ROUND_POWER_DOUBLED_TAG = "better_ai:round_power_doubled"
    const val ACTS_BEFORE_TARGET_POWER_DOUBLED_TAG = "better_ai:acts_before_target_power_doubled"
    const val TARGET_ALREADY_ACTED_POWER_DOUBLED_TAG = "better_ai:target_already_acted_power_doubled"
    const val DAMAGED_BY_TARGET_POWER_DOUBLED_TAG = "better_ai:damaged_by_target_power_doubled"
    const val DAMAGED_TARGET_POWER_DOUBLED_TAG = "better_ai:damaged_target_power_doubled"

    /**
     * The attacking stat after items the battle has made public.
     *
     * Held items were almost entirely absent from the damage path - only the Utility Umbrella reached
     * it, through the weather - while the battle tower hands them out on nearly every set. A Choice
     * Band is half again the attack it is computed from, and an AI that cannot see its own band is
     * wrong about its own damage, which is the number every other judgement is derived from.
     *
     * Only a revealed item counts, which for the opponent means one already seen. The AI's own items
     * are never hidden from it, so this is mostly the trainer learning what it is actually holding.
     */
    fun attack(
        value: BattleIntegerRange,
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange {
        val itemMultiplier = attackItemMultiplier(category, actor, LocalPublicItemState.activeItemId(state, actor))
        val abilityMultiplier = if (
            category == BattleMoveDamageCategory.PHYSICAL &&
                LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "hustle"
        ) 1.5 else 1.0
        return scale(value, itemMultiplier * abilityMultiplier)
    }

    private fun attackItemMultiplier(
        category: BattleMoveDamageCategory,
        actor: BattlePokemonStateView,
        item: String?,
    ): Double = when (item) {
        "choiceband" -> if (category == BattleMoveDamageCategory.PHYSICAL) 1.5 else 1.0
        "choicespecs" -> if (category == BattleMoveDamageCategory.SPECIAL) 1.5 else 1.0
        "lightball" -> if (canonical(actor.speciesId) == "pikachu") 2.0 else 1.0
        else -> 1.0
    }

    fun defence(
        value: BattleIntegerRange,
        stat: LocalPublicMoveDamageInputs.CombatStat,
        target: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = scale(value, defenceMultiplier(stat, LocalPublicItemState.activeItemId(state, target)))

    fun offensiveDefence(
        value: BattleIntegerRange,
        stat: LocalPublicMoveDamageInputs.CombatStat,
        pokemon: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange = defence(value, stat, pokemon, state)

    private fun defenceMultiplier(
        stat: LocalPublicMoveDamageInputs.CombatStat,
        item: String?,
    ): Double {
        val vest = if (
            stat == LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE && item == "assaultvest"
        ) 1.5 else 1.0
        // Eviolite needs to know the holder can still evolve, which the public state does not say. It
        // is left out rather than guessed: over-stating a defence makes the AI decline attacks that
        // would have worked, which is the more damaging way to be wrong.
        return vest
    }

    /**
     * Damage multipliers that apply after the stats, from items the battle has made public.
     *
     * Life Orb and Expert Belt scale the finished damage rather than a stat, so they are returned
     * separately and applied where the projection lands.
     */
    fun damageMultiplier(
        actor: BattlePokemonStateView,
        typeChartMultiplier: Double?,
        state: BattleStateView,
    ): Double = when (LocalPublicItemState.activeItemId(state, actor)) {
            "lifeorb" -> 1.3
            "expertbelt" -> if ((typeChartMultiplier ?: 1.0) > 1.0) 1.2 else 1.0
            else -> 1.0
        }

    /** Speed after deterministic modifiers from a publicly known held item. */
    fun speed(
        value: BattleIntegerRange,
        pokemon: BattlePokemonStateView,
        state: BattleStateView,
    ): BattleIntegerRange {
        val item = LocalPublicItemState.activeItemId(state, pokemon)
        val multiplier = when {
            item == "choicescarf" -> 1.5
            item in HALF_SPEED_ITEMS -> 0.5
            else -> 1.0
        }
        return scale(value, multiplier)
    }

    private fun scale(value: BattleIntegerRange, multiplier: Double) = BattleIntegerRange(
        minimum = (value.minimum * multiplier).toInt().coerceAtLeast(1),
        maximum = (value.maximum * multiplier).toInt().coerceAtLeast(1),
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private val HALF_SPEED_ITEMS = setOf(
        "ironball", "machobrace", "poweranklet", "powerband", "powerbelt",
        "powerbracer", "powerlens", "powerweight",
    )
}
